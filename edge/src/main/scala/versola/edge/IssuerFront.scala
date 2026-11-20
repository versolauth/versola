package versola.edge

import versola.util.http.{Controller, Observability}
import versola.util.{JWT, RoutingKey}
import zio.http.*
import zio.http.codec.PathCodec.trailing
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Random, URLayer, ZIO, ZLayer}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** The issuer as a browser and a public client reach it once there are several cells (versolauth/versola#547, #1044):
  * edge's own listener for it, which sends each request to the cell that holds what the request names.
  *
  * It has no protocol logic. It reads where a request belongs from what `auth` itself wrote into the values the
  * request carries (the routing key of a cookie, a code, a refresh token or a `request_uri`; the `cell` header of a
  * token), forwards the request as it came and relays the answer as it is. A key that names no cell of the deployment
  * is not refused here: the request goes where a new one would, and `auth` answers in its own shape.
  *
  * Only the issuer's public paths are served ([[IssuerFront.served]]): `auth` answers its internal routes on the same
  * port, and none of them is reachable through here.
  *
  * Not served: a client that authenticates with a certificate of its own. TLS ends here, so its certificate would not
  * reach `auth`; such a client keeps a cell's own listener.
  */
object IssuerFront extends Controller:
  type Env = Tracing & CellTable & IssuerFront.Upstream & EdgeConfig

  /** The client the cells are reached with: the application's own, held as a service of the listener. */
  final case class Upstream(client: Client)

  val upstream: URLayer[Client, Upstream] = ZLayer.fromFunction(Upstream(_))

  /** The first segment of every path served. */
  val served: Set[String] = Set("authorize", "par", "token", "userinfo", "introspect", "revoke", "logout", "challenge", ".well-known")

  /** The most of a request body that is read: the forms of the token endpoints and a pushed request object fit. */
  val BodyLimit: Int = 1024 * 1024

  private val ConversationCookie = "SSO_CONVERSATION"
  private val SessionCookie = "SSO_SESSION"
  private val RequestUriNamespace = "urn:ietf:params:oauth:request_uri:"

  /** What the hop itself owns and is not passed on, in either direction. */
  private val HopHeaders = Set("host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade", "te", "trailer")

  private case class CellHeader(cell: Option[String]) derives JsonDecoder

  private case class Moved(error: String, cell: Option[String]) derives JsonDecoder

  /** What of a request says where it belongs.
    *
    * @param path the segments of the path, the first of which is one of [[served]]
    * @param cookie the content of a cookie of the request
    * @param form a field of the request's urlencoded body
    * @param query a query parameter
    * @param bearer the token of the `Authorization` header, whatever its scheme
    */
  private[edge] def routingOf(
      path: List[String],
      cookie: String => Option[String],
      form: String => Option[String],
      query: String => Option[String],
      bearer: Option[String],
  ): CellRouting =
    // A value `auth` issues either starts with a routing key or is a token whose header names the cell.
    def ofValue(value: String): CellRouting =
      if value.startsWith(s"${RoutingKey.Version}.") then CellRouting.ByValue(value) else CellRouting.ByHeader(headerCell(value))
    path.headOption match
      case Some("challenge") => cookie(ConversationCookie).fold(CellRouting.Placement)(CellRouting.ByValue(_))
      case Some("token" | "revoke" | "introspect") =>
        form("code").orElse(form("refresh_token")).orElse(form("token")).fold(CellRouting.Placement)(ofValue)
      case Some("userinfo") => bearer.fold(CellRouting.Placement)(ofValue)
      case Some("authorize") =>
        query("request_uri").map(_.stripPrefix(RequestUriNamespace)).orElse(cookie(SessionCookie)).fold(CellRouting.Placement)(CellRouting.ByValue(_))
      case Some("logout") =>
        cookie(SessionCookie).orElse(query("id_token_hint").flatMap(sessionOf)).fold(CellRouting.Placement)(CellRouting.ByValue(_))
      case _ => CellRouting.Placement

  /** The cell a request goes to; `None` is the configured `auth`. A key that names no cell, or does not spell one, is
    * placed like a request with none. Fails only when cells are registered and none takes a new request. */
  private[edge] def cellOf(topology: CellTopology, routing: CellRouting, roll: Double): Either[CellRoutingError, Option[EdgeCell]] =
    CellRouting.route(topology, routing, roll) match
      case Left(_: CellRoutingError.Unknown | _: CellRoutingError.Malformed) => CellRouting.route(topology, CellRouting.Placement, roll)
      case other => other

  /** The `cell` JOSE header of a token, unverified: it only chooses an address, the token is verified by `auth`. */
  private def headerCell(token: String): Option[String] =
    scala.util.Try(String(Base64.getUrlDecoder.decode(token.takeWhile(_ != '.')), StandardCharsets.UTF_8)).toOption
      .flatMap(_.fromJson[CellHeader].toOption).flatMap(_.cell)

  /** The `sid` claim of an ID token given as a hint, which carries the routing key of the session's cell (#606). */
  private def sessionOf(idToken: String): Option[String] =
    idToken.split('.').lift(1)
      .flatMap(segment => scala.util.Try(String(Base64.getUrlDecoder.decode(segment), StandardCharsets.UTF_8)).toOption)
      .flatMap(_.fromJson[Json.Obj].toOption)
      .flatMap(_.get("sid")).flatMap(_.asString)

  private def formOf(body: Chunk[Byte]): Map[String, String] =
    String(body.toArray, StandardCharsets.UTF_8).split('&').toList.flatMap: pair =>
      pair.split("=", 2) match
        case Array(name, value) =>
          scala.util.Try(URLDecoder.decode(name, StandardCharsets.UTF_8) -> URLDecoder.decode(value, StandardCharsets.UTF_8)).toOption
        case _ => None
    .toMap

  /** The served paths under the methods the issuer's endpoints take, spelled out: a path that is not one of them
    * matches no route, so the listener answers `404` for it without a handler of ours running. */
  def routes: Routes[Env, Throwable] =
    Routes.fromIterable(
      for
        method <- List(Method.GET, Method.POST)
        first <- served.toList.sorted
        route <- List[Route[Env, Throwable]](
          method / first -> handler((request: Request) => serve(request)),
          method / first / trailing -> handler((_: Path, request: Request) => serve(request)),
        )
      yield route,
    )

  private def serve(request: Request): ZIO[Env, Throwable, Response] =
    ZIO.serviceWithZIO[EdgeConfig]: config =>
      // A partner's edge is not in front of the operator's issuer.
      if config.mode != EdgeMode.FirstParty then Observability.setError("issuer_path_not_served").as(Response.notFound)
      else forward(config, request.url.path.segments.toList, request)

  private def forward(config: EdgeConfig, segments: List[String], request: Request): ZIO[Env, Throwable, Response] =
    for
      body <- request.body.asChunk
      response <-
        if body.length > BodyLimit then Observability.setError("issuer_body_too_large").as(Response.status(Status.RequestEntityTooLarge))
        else
          val isForm = request.header(Header.ContentType).exists(_.mediaType == MediaType.application.`x-www-form-urlencoded`)
          val form = if isForm then formOf(body) else Map.empty[String, String]
          val bearer = request.headers.get("Authorization").map(_.trim).flatMap(_.split(' ').lift(1))
          val routing = routingOf(
            path = segments,
            cookie = name => request.cookie(name).map(_.content),
            form = form.get,
            query = name => request.url.queryParams.getAll(name).headOption,
            bearer = bearer,
          )
          for
            topology <- ZIO.serviceWithZIO[CellTable](_.current)
            roll <- Random.nextDouble
            answer <- cellOf(topology, routing, roll) match
              case Left(error) =>
                Observability.setError(CellRoutingError.code(error), Some(error.getMessage)).as(Response.status(Status.ServiceUnavailable))
              case Right(cell) =>
                send(config, cell, request, body).flatMap: first =>
                  // `auth` says the user is in another cell: asked once more there, for the two requests that are a body and nothing else.
                  val replayed = Option.when(first.status.code == 421 && segments.headOption.exists(Set("token", "par")))(first)
                    .flatMap(response => String(response._2.toArray, StandardCharsets.UTF_8).fromJson[Moved].toOption)
                    .collect { case Moved("moved", Some(to)) => to }
                    .flatMap(to => topology.cells.find(_.id == to))
                    .filterNot(target => cell.exists(_.id == target.id))
                  replayed.fold(ZIO.succeed(first))(target => send(config, Some(target), request, body))
                .map(relayed)
          yield answer
    yield response

  /** The answer of the cell, buffered: status and headers, and the body. */
  private type Answer = (Response, Chunk[Byte])

  extension (answer: Answer) private def status: Status = answer._1.status

  private def relayed(answer: Answer): Response =
    val kept = answer._1.headers.toList.filterNot(header => HopHeaders.contains(header.headerName.toLowerCase))
    Response(status = answer._1.status, headers = Headers(kept), body = Body.fromChunk(answer._2))

  private def send(config: EdgeConfig, cell: Option[EdgeCell], request: Request, body: Chunk[Byte]): ZIO[Upstream, Throwable, Answer] =
    ZIO.serviceWithZIO[Upstream]: upstream =>
      val httpClient = upstream.client
      val base = cell.fold(config.internalUrl)(_.internalUrl)
      // A cell is authenticated by its own pins; the configured auth by those of the configuration, as edge's own calls are.
      val pins = cell.fold(
        Option.when(config.versolaInternalTrustedCertificates.nonEmpty)(EdgeConfig.pinnedTrust(config.versolaInternalTrustedCertificates)),
      )(_.trust)
      val client = pins.fold(httpClient)(httpClient.ssl)
      val url = base.copy(path = base.path ++ request.url.path, queryParams = request.url.queryParams)
      // The address of the caller as this edge knows it, in both headers `auth` may be told to read: the last value of
      // `X-Forwarded-For` is the one a proxy wrote, so it is appended, and `X-Real-IP` is this hop's alone.
      val caller = ClientAddress.trustedLiteral(request, config.clientIp, config.clientIpHeader)
      val passed = request.headers.toList.filterNot: header =>
        val name = header.headerName.toLowerCase
        HopHeaders.contains(name) || name == "x-real-ip" || name == "x-forwarded-for"
      val forwardedFor = (request.headers.get("X-Forwarded-For").toList ++ caller).mkString(", ")
      val headers = Headers(passed)
        .addHeaders(Headers(caller.map(Header.Custom("X-Real-IP", _)).toList))
        .addHeaders(Headers(Option.when(forwardedFor.nonEmpty)(Header.Custom("X-Forwarded-For", forwardedFor)).toList))
      val outgoing = Request(method = request.method, url = url, headers = headers, body = Body.fromChunk(body))
      ZIO.scoped(client.request(outgoing).flatMap(response => response.body.asChunk.map(response -> _)))
