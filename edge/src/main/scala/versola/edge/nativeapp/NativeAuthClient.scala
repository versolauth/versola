package versola.edge.nativeapp

import versola.edge.{ClientCertificateFiles, EdgeConfig}
import versola.util.PrivateClientCertificate
import zio.http.*
import zio.{Chunk, Task, URLayer, ZIO, ZLayer}

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

  val live: URLayer[Client & EdgeConfig & ClientCertificateFiles, NativeAuthClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  /** Raised when the native endpoints are reached on an edge whose configuration carries no
    * `native` block. The controller answers 404 before this can happen; this is the guard
    * for any other caller. */
  case object NotConfigured extends RuntimeException("edge has no `native` configuration block")

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      certificateFiles: ClientCertificateFiles,
  ) extends NativeAuthClient:

    override def par(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
    ): Task[Relayed] =
      post("par", clientId, certificate, form, Headers.empty)

    override def token(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      post("token", clientId, certificate, form, forwardedHeaders)

    override def revoke(
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      post("revoke", clientId, certificate, form, forwardedHeaders)

    private def post(
        path: String,
        clientId: String,
        certificate: PrivateClientCertificate.Material,
        form: Form,
        forwardedHeaders: Headers,
    ): Task[Relayed] =
      for
        native <- ZIO.fromOption(config.native).orElseFail(NotConfigured)
        certificateConfig <- certificateFiles.present(certificate)
        ssl = ClientSSLConfig.FromClientAndServerCert(
          ClientSSLConfig.FromCertFile(native.trustedCertificates),
          certificateConfig,
        )
        // RFC 8705 §2.1: the certificate is the credential, `client_id` names whose it is.
        identified =
          if form.get("client_id").isDefined then form
          else form.append(FormField.simpleField("client_id", clientId))
        url = native.authMutualTlsUrl / path
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
