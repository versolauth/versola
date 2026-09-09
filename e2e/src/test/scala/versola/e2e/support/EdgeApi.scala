package versola.e2e.support

import zio.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.ast.Json

/** How a caller authenticates itself to the edge.
  *
  * Edge accepts either the browser's `EDGE_SESSION` cookie or a bearer token, and the two are
  * not interchangeable: only the cookie carries a preset id, and only the cookie path can
  * refresh an expired token. Tests that care about the difference say which one they mean.
  */
enum EdgeAuth:
  case Bearer(accessToken: String)
  case Session(content: String)
  case None

/** Thin HTTP client for the edge's own surface: the web login it drives on a browser's
  * behalf, the logout endpoints, and the resource proxy.
  *
  * Redirects are never followed — each hop is its own assertion, the same way [[OAuthClient]]
  * treats the authorization flow.
  */
final class EdgeApi(client: Client, config: E2EConfig):

  private val edgeAuthorization = Authorization.Basic("edge", config.edgeInternalSecret)

  /** Where the edge expects the authorization code to come back to, and therefore what a
    * preset must register as its `redirectUri` and its client as a redirect URI.
    */
  val completeUri: String = s"${config.edgeUrl}/complete"

  /** GET /login/{presetId} — the entry point a first-party app links to. Answers a 303 to the
    * OP's `/authorize`, having minted and stored the PKCE verifier and state itself.
    */
  def login(presetId: String, params: (String, String)*): Task[Response] =
    send(Method.GET, s"/login/$presetId", params, None, EdgeAuth.None)

  /** GET /complete — the OP's redirect back. Carries either `code`+`state` or `error`+`state`;
    * both parameters are optional here so a test can present neither, or only one.
    */
  def complete(params: (String, String)*): Task[Response] =
    send(Method.GET, "/complete", params, None, EdgeAuth.None)

  /** GET /logout/{presetId} — hands the browser on to the OP's RP-initiated logout. */
  def logout(presetId: String): Task[Response] =
    send(Method.GET, s"/logout/$presetId", Nil, None, EdgeAuth.None)

  /** GET /logout/frontchannel — invoked by the OP in a hidden iframe with `iss`/`sid`, or
    * first-party by the browser with no parameters at all.
    */
  def frontChannelLogout(
      iss: Option[String] = scala.None,
      sid: Option[String] = scala.None,
      session: EdgeAuth = EdgeAuth.None,
  ): Task[Response] =
    val params = List(iss.map("iss" -> _), sid.map("sid" -> _)).flatten
    send(Method.GET, "/logout/frontchannel", params, None, session)

  /** POST /logout/backchannel — the OP calling server-to-server with a signed logout token. */
  def backChannelLogout(logoutToken: Option[String]): Task[Response] =
    val form = logoutToken.map("logout_token" -> _).toList
    for
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}/logout/backchannel")).mapError(RuntimeException(_))
      request = Request.post(url, formBody(form))
        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
      response <- Client.batched(request).provide(ZLayer.succeed(client))
    yield response

  /** GET /permissions/me — what the console reads to decide which affordances to show. */
  def permissions(auth: EdgeAuth, resources: List[String] = Nil): Task[ApiResult] =
    send(Method.GET, "/permissions/me", resources.map("resource" -> _), None, auth)
      .flatMap(ApiResult.of)

  /** A call through the resource proxy, exactly as the browser makes it. */
  def proxy(
      method: Method,
      resourceId: String,
      path: String,
      auth: EdgeAuth,
      body: Option[Json] = scala.None,
      query: List[(String, String)] = Nil,
      headers: List[(String, String)] = Nil,
  ): Task[ApiResult] =
    for
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}/resources/$resourceId$path"))
        .mapError(RuntimeException(_))
      base = Request(
        method = method,
        url = url.addQueryParams(query),
        body = body.fold(Body.empty)(json => Body.fromString(zio.json.EncoderOps(json).toJson)),
      )
      withType = body.fold(base)(_ => base.addHeader(Header.ContentType(MediaType.application.json)))
      withHeaders = headers.foldLeft(withType)((request, header) => request.addHeader(header._1, header._2))
      response <- Client.batched(authenticate(withHeaders, auth)).provide(ZLayer.succeed(client))
      result <- ApiResult.of(response)
    yield result

  /** POST /service/configuration/sync — makes edge reload its client, preset, resource,
    * role and permission caches from central at once, instead of waiting out
    * `configurationCacheRefreshInterval`. Every fixture a test registers in central has to
    * be followed by one of these before the edge will act on it.
    */
  def syncConfiguration: Task[Unit] =
    for
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}/service/configuration/sync")).mapError(RuntimeException(_))
      response <- Client.batched(Request.post(url, Body.empty).addHeader(edgeAuthorization))
        .provide(ZLayer.succeed(client))
      _ <- ZIO.unless(response.status.isSuccess)(
        response.body.asString.flatMap(body =>
          ZIO.fail(RuntimeException(s"edge sync failed: status=${response.status} body=$body")),
        ),
      )
    yield ()

  /** Drives everything up to the OP's redirect back, without letting the edge consume the
    * result. Tests that check what `/complete` does with a callback — replayed, mismatched,
    * tampered with — need the parameters in hand before the edge sees them.
    *
    * The redirect chain is walked by hand rather than by a redirect-following client, because
    * the state and conversation cookies have to be carried across hops that belong to two
    * different origins.
    */
  def authorize(auth: OAuthClient, presetId: String, login: String, password: String): Task[EdgeCallback] =
    for
      started <- this.login(presetId)
      authorizeUrl <- ZIO.fromOption(started.header(Header.Location).map(_.url.encode))
        .orElseFail(RuntimeException(s"/login/$presetId did not redirect (status=${started.status})"))
      authorized <- auth.probe(Method.GET, authorizeUrl)
      conversation <- ZIO.fromOption(OAuthClient.extractConversationCookie(authorized))
        .orElseFail(RuntimeException(s"the OP started no conversation (status=${authorized.status})"))
      challenge <- auth.getChallenge(conversation)
      submitted <- auth.submitLoginPassword(conversation, login, password, challenge.csrf)
      back <- ZIO.fromOption(submitted.response.header(Header.Location).map(_.url.encode))
        .orElseFail(RuntimeException(s"the OP did not redirect back (status=${submitted.response.status})"))
      backUrl <- ZIO.fromEither(URL.decode(back)).mapError(RuntimeException(_))
      code <- ZIO.fromOption(backUrl.queryParams.getAll("code").headOption)
        .orElseFail(RuntimeException(s"the OP returned no authorization code: $back"))
      state <- ZIO.fromOption(backUrl.queryParams.getAll("state").headOption)
        .orElseFail(RuntimeException(s"the OP returned no state: $back"))
    yield EdgeCallback(code, state)

  /** Signs a user in the way a browser does, all the way to the `EDGE_SESSION` cookie. */
  def browserLogin(auth: OAuthClient, presetId: String, login: String, password: String): Task[EdgeSession] =
    for
      callback <- authorize(auth, presetId, login, password)
      completed <- complete("code" -> callback.code, "state" -> callback.state)
      cookie <- ZIO.fromOption(EdgeApi.sessionCookie(completed))
        .orElseFail(RuntimeException(s"/complete set no EDGE_SESSION cookie (status=${completed.status})"))
    yield EdgeSession(cookie, completed)

  private def authenticate(request: Request, auth: EdgeAuth): Request =
    auth match
      case EdgeAuth.Bearer(token) => request.addHeader(Authorization.Bearer(token))
      case EdgeAuth.Session(content) =>
        request.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(EdgeApi.sessionCookieName, content))))
      case EdgeAuth.None => request

  private def send(
      method: Method,
      path: String,
      query: Seq[(String, String)],
      body: Option[Json],
      auth: EdgeAuth,
  ): Task[Response] =
    for
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}$path")).mapError(RuntimeException(_))
      base = Request(
        method = method,
        url = url.addQueryParams(query.toList),
        body = body.fold(Body.empty)(json => Body.fromString(zio.json.EncoderOps(json).toJson)),
      )
      response <- Client.batched(authenticate(base, auth)).provide(ZLayer.succeed(client))
    yield response

  private def formBody(fields: List[(String, String)]): Body =
    Body.fromString(
      fields
        .map((name, value) => s"${java.net.URLEncoder.encode(name, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}")
        .mkString("&"),
    )

/** The parameters the OP hands back to the edge's `/complete`. */
case class EdgeCallback(code: String, state: String)

/** What the browser keeps after a completed edge login. */
case class EdgeSession(cookie: String, response: Response):
  /** The `<presetId>:<accessToken>` cookie split into its halves — the proxy reads both. */
  val presetId: String = cookie.takeWhile(_ != ':')
  val accessToken: String = cookie.dropWhile(_ != ':').drop(1)
  val auth: EdgeAuth = EdgeAuth.Session(cookie)

object EdgeApi:
  val sessionCookieName = "EDGE_SESSION"

  val live: ZLayer[Client & E2EConfig, Nothing, EdgeApi] =
    ZLayer.fromFunction(EdgeApi(_, _))

  /** The `EDGE_SESSION` value a response sets, or `None` when it sets none. A logout clears
    * the cookie by setting it empty, which this reports as an empty string rather than as
    * absent — the difference is exactly what a logout test asserts on.
    */
  def sessionCookie(response: Response): Option[String] =
    response.headers.getAll(Header.SetCookie)
      .collectFirst { case header if header.value.name == sessionCookieName => header.value.content }
