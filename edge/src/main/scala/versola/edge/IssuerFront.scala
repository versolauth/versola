package versola.edge

import versola.util.http.{Controller, Observability}
import zio.http.*
import zio.http.codec.PathCodec.trailing
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Random, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The pages of a sign-in as a browser reaches them once there are several cells (versolauth/versola#547, #1044).
  *
  * A client of a deployment with cells works through edge: its codes, tokens and userinfo calls are edge's own and are
  * routed by cell already ([[SSOClient]], `NativeAuthClient`). What is left is the browser, which edge sends to the
  * issuer to sign in and which then talks to `auth` itself: `/authorize`, the steps under `/challenge` and `/logout`.
  * Those are served here, on edge's own port, so that the issuer's address can be an edge's and each request is sent to
  * the cell that holds the sign-in.
  *
  * It has no protocol logic. It reads where a request belongs from what `auth` itself wrote into what the browser
  * carries (the routing key of the conversation or session cookie, of a `request_uri`, of a handoff, of the `sid` of
  * an ID token hint), forwards the request as it came and relays the answer as it is. A key that names no cell of the deployment is
  * not refused here: the request goes where a new one would, and `auth` answers with its own page.
  *
  * With no cell registered the requests go to the `auth` edge is configured with, so the issuer's address may be an
  * edge's whether or not there are cells. The issuer's endpoints for clients (`/token`, `/par`, `/userinfo`, the rest)
  * are not served: a client that calls them itself reaches `auth` at an address of its own.
  */
object IssuerFront extends Controller:
  type Env = Tracing & CellTable & IssuerFront.Upstream & EdgeConfig

  /** The client the cells are reached with: the application's own, held as a service of the routes. */
  final case class Upstream(client: Client)

  val upstream: URLayer[Client, Upstream] = ZLayer.fromFunction(Upstream(_))

  /** The route patterns served, which the address limit and the admission tiers name ([[AddressLimit]], [[EdgeAdmission]]). */
  val Authorize = "/authorize"
  val Challenge = "/challenge"
  val Logout = "/logout"

  /** The most of a request body that is read: the forms of the sign-in steps are far under it. */
  val BodyLimit: Int = 256 * 1024

  private val ConversationCookie = "__Host-SSO_CONVERSATION"
  private val SessionCookie = "SSO_SESSION"
  private val UserAgentCookie = "SSO_USER_AGENT_ID"

  /** The issuer's own cookies. Where the issuer's address is this edge's, a browser sends them with every request to
    * the host (their path is `/`), the ones edge proxies to a resource among them: they are the session of the person
    * at the issuer and the state of a sign-in, and an upstream that received them could use them there. Only the
    * issuer's pages are given them ([[routes]]); the proxy drops them. */
  val IssuerCookies: Set[String] = Set(ConversationCookie, SessionCookie, UserAgentCookie)
  private val HandoffParameter = "handoff"
  private val RequestUriNamespace = "urn:ietf:params:oauth:request_uri:"

  /** What the hop itself owns and is not passed on, in either direction. */
  private val HopHeaders = Set("host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade", "te", "trailer")

  /** What of a request says where it belongs.
    *
    * @param path the segments of the path
    * @param cookie the content of a cookie of the request
    * @param query a query parameter
    */
  private[edge] def routingOf(path: List[String], cookie: String => Option[String], query: String => Option[String]): CellRouting =
    path.headOption match
      // A sign-in one cell hands to another names the cell it is for, and the cookie the browser still holds is the
      // first cell's (versolauth/versola#1046).
      case Some("challenge") if path == List("challenge", "handoff") =>
        query(HandoffParameter).orElse(cookie(ConversationCookie)).fold(CellRouting.Placement)(CellRouting.ByValue(_))
      case Some("challenge") => cookie(ConversationCookie).fold(CellRouting.Placement)(CellRouting.ByValue(_))
      // The pushed request is in one cell and nowhere else, so it decides before the browser's session does.
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

  /** The `sid` claim of an ID token given as a hint, which carries the routing key of the session's cell (#606).
    * Unverified: it only chooses an address, the hint is verified by `auth`. */
  private def sessionOf(idToken: String): Option[String] =
    idToken.split('.').lift(1)
      .flatMap(segment => scala.util.Try(String(Base64.getUrlDecoder.decode(segment), StandardCharsets.UTF_8)).toOption)
      .flatMap(_.fromJson[Json.Obj].toOption)
      .flatMap(_.get("sid")).flatMap(_.asString)

  /** `/authorize` and `/logout` as they are, and everything under `/challenge`. Edge's own `/logout/<preset>` and
    * `/logout/frontchannel` are other paths. */
  def routes: Routes[Env, Throwable] =
    Routes.fromIterable(
      List(Method.GET, Method.POST).flatMap: method =>
        List[Route[Env, Throwable]](
          method / "authorize" -> handler((request: Request) => serve(request)),
          method / "logout" -> handler((request: Request) => serve(request)),
          method / "challenge" -> handler((request: Request) => serve(request)),
          method / "challenge" / trailing -> handler((_: Path, request: Request) => serve(request)),
        ),
    )

  private def serve(request: Request): ZIO[Env, Throwable, Response] =
    ZIO.serviceWithZIO[EdgeConfig]: config =>
      // A partner's edge is not in front of the operator's issuer.
      if config.mode != EdgeMode.FirstParty then Observability.setError("issuer_path_not_served").as(Response.notFound)
      else forward(config, request)

  private def forward(config: EdgeConfig, request: Request): ZIO[Env, Throwable, Response] =
    request.body.asChunk.flatMap: body =>
      if body.length > BodyLimit then Observability.setError("issuer_body_too_large").as(Response.status(Status.RequestEntityTooLarge))
      else
        val routing = routingOf(
          path = request.url.path.segments.toList,
          cookie = name => request.cookie(name).map(_.content),
          query = name => request.url.queryParams.getAll(name).headOption,
        )
        for
          topology <- ZIO.serviceWithZIO[CellTable](_.current)
          roll <- Random.nextDouble
          answer <- cellOf(topology, routing, roll) match
            case Left(error) =>
              Observability.setError(CellRoutingError.code(error), Some(error.getMessage)).as(Response.status(Status.ServiceUnavailable))
            case Right(cell) => send(config, cell, request, body)
        yield answer

  private def send(config: EdgeConfig, cell: Option[EdgeCell], request: Request, body: Chunk[Byte]): ZIO[Upstream, Throwable, Response] =
    ZIO.serviceWithZIO[Upstream]: upstream =>
      val base = cell.fold(config.internalUrl)(_.internalUrl)
      // A cell is authenticated by its own pins; the configured auth by those of the configuration, as edge's own calls are.
      val pins = cell.fold(
        Option.when(config.versolaInternalTrustedCertificates.nonEmpty)(EdgeConfig.pinnedTrust(config.versolaInternalTrustedCertificates)),
      )(_.trust)
      val client = pins.fold(upstream.client)(upstream.client.ssl)
      val url = base.copy(path = base.path ++ request.url.path, queryParams = request.url.queryParams)
      // The address of the caller as this edge knows it, in both headers `auth` may be told to read: the last value of
      // `X-Forwarded-For` is the one a proxy wrote, so it is appended, and `X-Real-IP` is this hop's alone.
      val caller = ClientAddress.trustedLiteral(request, config.clientIp, config.clientIpHeader)
      val passed = request.headers.toList.filterNot: header =>
        val name = header.headerName.toLowerCase
        HopHeaders.contains(name) || name == "x-real-ip" || name == "x-forwarded-for" || name == "cookie"
      // Edge's own session is not auth's to see, where the issuer's address and edge's are one.
      val cookies = request.cookies.filterNot(_.name == EdgeSessionCookie.name).map(cookie => s"${cookie.name}=${cookie.content}").mkString("; ")
      val forwardedFor = (request.headers.get("X-Forwarded-For").toList ++ caller).mkString(", ")
      val headers = Headers(passed)
        .addHeaders(Headers(Option.when(cookies.nonEmpty)(Header.Custom("Cookie", cookies)).toList))
        .addHeaders(Headers(caller.map(Header.Custom("X-Real-IP", _)).toList))
        .addHeaders(Headers(Option.when(forwardedFor.nonEmpty)(Header.Custom("X-Forwarded-For", forwardedFor)).toList))
      val outgoing = Request(method = request.method, url = url, headers = headers, body = Body.fromChunk(body))
      ZIO.scoped(client.request(outgoing).flatMap(response => response.body.asChunk.map(relayed(response, _))))

  private def relayed(response: Response, body: Chunk[Byte]): Response =
    val kept = response.headers.toList.filterNot(header => HopHeaders.contains(header.headerName.toLowerCase))
    Response(status = response.status, headers = Headers(kept), body = Body.fromChunk(body))
