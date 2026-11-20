package versola.edge.nativeapp

import versola.edge.{CellRouting, CellRoutingError, CellTable, ClientCertificateFiles, EdgeCell, EdgeConfig}
import versola.util.http.Observability
import versola.util.{JWT, PrivateClientCertificate, RoutingKey}
import zio.http.*
import zio.json.*
import zio.{Chunk, Random, Task, URLayer, ZIO, ZLayer}

/** The back channel of the native flow (#420): calls to auth's own mutual-TLS listener
  * (`MPORT`, #417) authenticated as a native client by the certificate central synced to this
  * edge, with no TLS terminator in between.
  *
  * Every call answers auth's response whole -- status, the headers the device has to see, and
  * the buffered body -- because the endpoints relay it untouched: a `use_dpop_nonce` refusal and
  * the `DPoP-Nonce` it carries are auth's to state, not edge's to reinterpret.
  *
  * Connections are pooled: zio-http keys its pool by location *and* SSL configuration, and the
  * configuration built here is equal for every call with the same certificate, so a handshake
  * is paid once per pooled connection rather than per request.
  *
  * Where cells are registered (versolauth/versola#559) a call goes to the mutual-TLS listener of the cell that holds
  * the user, by the same rules as `SSOClient`: a pushed request is placed in an open cell, a code or a refresh token
  * goes to the cell its routing key names, a token to revoke to the cell of its key or of its `cell` header. A cell
  * that answers `421 moved` to `/par` or `/token` is asked once more at the cell it names. With no cell registered,
  * and for a value without a key, the call goes to the listener the `native` block names, as before.
  */
trait NativeAuthClient:
  /** RFC 9126 `POST /par`. */
  def par(
      clientId: String,
      certificate: PrivateClientCertificate.Material,
      form: Form,
  ): Task[NativeAuthClient.Relayed]

  /** `POST /token` carrying the device's `DPoP` proof exactly as it arrived. */
  def token(
      clientId: String,
      certificate: PrivateClientCertificate.Material,
      form: Form,
      forwardedHeaders: Headers,
  ): Task[NativeAuthClient.Relayed]

  /** RFC 7009 `POST /revoke`. */
  def revoke(
      clientId: String,
      certificate: PrivateClientCertificate.Material,
      form: Form,
      forwardedHeaders: Headers,
  ): Task[NativeAuthClient.Relayed]

object NativeAuthClient:

  /** What auth answered, buffered so it can be relayed after the pooled connection is
    * released. */
  final case class Relayed(status: Status, headers: Headers, body: Chunk[Byte]):
    def bodyAsString: String = String(body.toArray, java.nio.charset.StandardCharsets.UTF_8)

    /** The response as the device sees it. Only the headers that carry protocol meaning are
      * copied -- connection-level ones (`Content-Length`, `Connection`, `Transfer-Encoding`)
      * belong to the hop they arrived on. */
    def toResponse: Response =
      val kept = headers.toList.filter(h => RelayedHeaders.contains(h.headerName.toLowerCase))
      Response(status = status, headers = Headers(kept), body = Body.fromChunk(body))

  /** RFC 9449 §8/§9 `DPoP-Nonce` and the `WWW-Authenticate` challenge that may accompany it,
    * the RFC 6749 §5.1 caching headers, and the body's type. */
  val RelayedHeaders: Set[String] =
    Set("content-type", "cache-control", "pragma", "dpop-nonce", "www-authenticate")

  /** The request headers a device sends that auth has to see as sent: the proof itself, and
    * the idempotency key a refresh may carry. */
  val ForwardedHeaders: Set[String] = Set("dpop", "idempotency-key")

  val live: URLayer[Client & EdgeConfig & ClientCertificateFiles & CellTable, NativeAuthClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  /** The cell a native call belongs to has no mutual-TLS listener, or pins nothing to authenticate it by: the
    * certificate is not presented anywhere else. */
  final case class CellUnreachable(cell: String)
    extends RuntimeException(s"cell '$cell' has no pinned mutual-TLS listener, so a native client cannot be served from it")

  private case class Moved(error: String, cell: Option[String]) derives JsonDecoder

  private case class CellHeader(cell: Option[String]) derives JsonDecoder

  /** What a device is told for a value whose routing key names no cell of this deployment: it was never issued here. */
  private val InvalidGrant: Relayed = Relayed(
    status = Status.BadRequest,
    headers = Headers(Header.ContentType(MediaType.application.json), Header.CacheControl.NoStore),
    body = Chunk.fromArray("""{"error":"invalid_grant"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
  )

  /** Raised when the native endpoints are reached on an edge whose configuration carries no
    * `native` block. The controller answers 404 before this can happen; this is the guard
    * for any other caller. */
  case object NotConfigured extends RuntimeException("edge has no `native` configuration block")

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      certificateFiles: ClientCertificateFiles,
      cells: CellTable,
  ) extends NativeAuthClient:

    private lazy val serverTrust: Option[ClientSSLConfig] =
      config.native.map(native => EdgeConfig.pinnedTrust(native.trustedCertificates))

    override def par(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
    ): Task[Relayed] =
      routed(CellRouting.Placement, replay = true)(post(_, "par", clientId, certificate, form, Headers.empty))

    override def token(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      routed(presented(form, "code").orElse(presented(form, "refresh_token")), replay = true)(
        post(_, "token", clientId, certificate, form, forwardedHeaders),
      )

    override def revoke(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      // RFC 7009 takes either kind of token: a refresh token carries its cell in the routing key, an access token in
      // its header.
      val routing = field(form, "token").map: token =>
        if token.startsWith(s"${RoutingKey.Version}.") then ZIO.succeed(CellRouting.ByValue(token))
        else JWT.parseHeader[CellHeader](token).map(_.cell).orElseSucceed(None).map(CellRouting.ByHeader(_))
      ZIO.foreach(routing)(identity).flatMap(routing =>
        routed(routing, replay = false)(post(_, "revoke", clientId, certificate, form, forwardedHeaders)),
      )

    private def field(form: Form, name: String): Option[String] = form.get(name).flatMap(_.stringValue)

    private def presented(form: Form, name: String): Option[CellRouting] = field(form, name).map(CellRouting.ByValue(_))

    /** Sends the call to the cell `routing` names (`None`: nothing in the call names one, so the configured listener),
      * and once more to the cell a `421 moved` answer names when `replay` allows it. A value naming a cell this
      * deployment does not have is answered here, as the grant it is not. */
    private def routed(routing: CellRouting, replay: Boolean)(call: Option[EdgeCell] => Task[Relayed]): Task[Relayed] =
      routed(Some(routing), replay)(call)

    private def routed(routing: Option[CellRouting], replay: Boolean)(call: Option[EdgeCell] => Task[Relayed]): Task[Relayed] =
      (for
        topology <- cells.current
        roll <- Random.nextDouble
        initial <- ZIO.foreach(routing)(routing => ZIO.fromEither(CellRouting.route(topology, routing, roll))).map(_.flatten)
        first <- call(initial)
        moved = Option.when(replay && first.status.code == 421)(first.bodyAsString.fromJson[Moved].toOption).flatten
          .collect { case Moved("moved", Some(cell)) => cell }
          .flatMap(to => topology.cells.find(_.id == to))
          .filterNot(target => initial.exists(_.id == target.id))
        answer <- moved.fold(ZIO.succeed(first))(target => call(Some(target)))
      yield answer).catchSome:
        case error @ (_: CellRoutingError.Unknown | _: CellRoutingError.Malformed) =>
          Observability.setError(CellRoutingError.code(error), Some(error.getMessage)).as(InvalidGrant)
        case error: CellRoutingError =>
          Observability.setError(CellRoutingError.code(error), Some(error.getMessage)) *> ZIO.fail(error)

    private def post(
        cell: Option[EdgeCell],
        path: String,
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      for
        native <- ZIO.fromOption(config.native).orElseFail(NotConfigured)
        certificateConfig <- certificateFiles.present(certificate)
        // A cell is reached at its own listener and authenticated by its own pins, never by the configured ones.
        target <- cell match
          case None => ZIO.succeed((native.authMutualTlsUrl, serverTrust.get))
          case Some(cell) =>
            ZIO.fromOption(cell.mtlsUrl.zip(cell.trust)).orElseFail(CellUnreachable(cell.id))
        (base, trust) = target
        ssl = ClientSSLConfig.FromClientAndServerCert(trust, certificateConfig)
        // RFC 8705 §2.1: the certificate is the credential, `client_id` names whose it is.
        identified =
          if form.get("client_id").isDefined then form
          else form.append(FormField.simpleField("client_id", clientId))
        url = base / path
        request = Request
          .post(url, Body.fromURLEncodedForm(identified))
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          .addHeaders(forwardedHeaders)
        relayed <- ZIO.scoped(
          httpClient.ssl(ssl).request(request).flatMap(response =>
            response.body.asChunk.map(Relayed(response.status, response.headers, _)),
          ),
        )
      yield relayed
