package versola.loadgen.protocol

import zio.http.*
import zio.{Duration, Ref, UIO, ZIO, durationInt}

/** A stand-in for auth and edge, driven by `TestClient`, good enough to exercise the request
  * shapes and the conversation state machine without a running stack.
  *
  * It renders the same page shape the SUT does (`versola-step` meta tag plus the inlined
  * `window.__VERSOLA_FORM__` carrying the csrf) and answers `303`s with the same cookies, which
  * is the part this module's correctness depends on. It is not a second implementation of auth:
  * nothing here validates a credential.
  */
object StubSut:
  val authUrl = "http://auth.test"
  val edgeUrl = "http://edge.test"
  val redirectUri = "app://callback"
  val origin = "https://versola.test"
  val rpId = "versola.test"
  val csrf = "csrf-token-1"
  val conversation = "conversation-1"
  val ssoSession = "sso-session-1"
  val code = "authorization-code-1"
  val requestTimeout: Duration = 2.seconds

  // The edge half of the stub (§8.4). `edgeSession` is shaped the way edge shapes the cookie's
  // content -- `<presetId>:<accessToken>` -- because the driver passes it back opaquely and a
  // value that did not look like one would hide a client that tried to parse it.
  val preset = "web-preset"
  val edgeState = "edge-state-1"
  val edgeSession = "web-preset:access-token-1"
  val rotatedEdgeSession = "web-preset:access-token-2"
  val edgeCookieTtl: Duration = 30.minutes
  val postLoginRedirect = "https://app.test/home"

  // The preset's `post_logout_redirect_uri`, which edge appends to the auth logout it redirects
  // to and auth binds its confirmation token to. Present here because a preset that has one is
  // the case where a driver can silently break the binding by not carrying it through.
  val postLogoutRedirect = "https://app.test/goodbye"
  val logoutCsrf = "logout-csrf-1"

  val authLogoutUrl: String =
    authUrl + "/logout?post_logout_redirect_uri=" + java.net.URLEncoder.encode(postLogoutRedirect, "UTF-8")

  /** What edge's `/login/{presetId}` redirects to: an `/authorize` URL edge built, carrying
    * edge's own `state` and `/complete` as the `redirect_uri`. Percent-encoded, as a real
    * `Location` header is -- the driver reads this value back off a parsed URL, so an
    * unencoded literal here would not round-trip and the test would be asserting on the
    * encoder rather than on the flow.
    */
  val authorizeUrl: String =
    authUrl + "/authorize?response_type=code&client_id=web-otp&redirect_uri=" +
      java.net.URLEncoder.encode(edgeUrl + "/complete", "UTF-8") +
      "&scope=openid+phone&state=" + edgeState + "&code_challenge=Y2hhbGxlbmdl&code_challenge_method=S256"

  /** Where auth ends a web conversation: back at edge, not at a client redirect URI. */
  val edgeCodeRedirect: String = edgeUrl + "/complete?code=" + code + "&state=" + edgeState

  /** The other way auth can end one: a refusal, which lands on the same endpoint. */
  val refusalError = "access_denied"
  val edgeErrorRedirect: String = edgeUrl + "/complete?error=" + refusalError + "&state=" + edgeState

  val publicClient: ClientRegistration = ClientRegistration(ClientCreds("mobile-otp", None), redirectUri)
  val confidentialClient: ClientRegistration = ClientRegistration(ClientCreds("web-otp", Some("s3cret")), redirectUri)

  val registry: ClientRegistry = ClientRegistry(
    Map(
      publicClient.creds.clientId -> publicClient,
      confidentialClient.creds.clientId -> confidentialClient,
      "mobile-otp-password" -> ClientRegistration(ClientCreds("mobile-otp-password", None), redirectUri),
      "mobile-passkey" -> ClientRegistration(ClientCreds("mobile-passkey", None), redirectUri),
    ),
    publicClient.creds.clientId,
  )

  val tokenBody: String =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":900,"refresh_token":"rt-1","id_token":"it-1","scope":"openid phone"}"""

  /** The confirmation page. Auth renders it through the same `FormRenderInfo` machinery as a
    * challenge page, so the token arrives in the same `window.__VERSOLA_FORM__` blob and the
    * driver reads it with the same one compiled pattern.
    */
  val logoutConfirmPage: String =
    """<!DOCTYPE html><html><head>""" +
      """<script>window.__VERSOLA_FORM__ = {"csrf":"""" + logoutCsrf + """"};</script></head><body></body></html>"""

  def page(step: String): String =
    """<!DOCTYPE html><html><head><meta name="versola-step" content="""" + step + """">""" +
      """<script>window.__VERSOLA_FORM__ = {"csrf":"""" + csrf + """"};</script></head><body></body></html>"""

  def passkeyOptions(challenge: String): String =
    """{"publicKey":{"challenge":"""" + challenge + """","rpId":"""" + rpId + """","userVerification":"required"}}"""

  def creationOptions(challenge: String): String =
    """{"publicKey":{"challenge":"""" + challenge + """","rp":{"id":"""" + rpId + """"}}}"""

  def codeRedirect: String = redirectUri + "?code=" + code + "&state=whatever"

  /** Every request the stub saw, in order, as `METHOD path`. */
  /** `ssoSessionLive` is the stub's own view of whether auth still holds the SSO session, which
    * is the only way to tell a logout that happened from one that was merely navigated to.
    */
  final case class Recorder(seen: Ref[Vector[Request]], ssoSessionLive: Ref[Boolean], pending: Ref[Boolean]):
    def ssoLive: UIO[Boolean] = ssoSessionLive.get

    /** Whether edge is still holding the `pending_logins` record the login created. */
    def pendingLogin: UIO[Boolean] = pending.get

    def paths: UIO[Vector[String]] = seen.get.map(_.map(request => request.method.name + " " + request.url.path.toString))

    def formOf(path: String): UIO[Option[Map[String, String]]] =
      seen.get.flatMap: requests =>
        requests.findLast(_.url.path.toString == path) match
          case None => ZIO.none
          case Some(request) => request.body.asString.map(body => Some(parseForm(body))).orDie

    /** The query parameters of the first request to a path -- the assertions that care want the
      * one the flow made, not the retry or the cleanup that followed it.
      */
    def queryOf(path: String): UIO[Option[Map[String, String]]] =
      seen.get.map(_.find(_.url.path.toString == path).map(_.url.queryParams.map.map((name, values) => name -> values.head).toMap))

    def headerOf(path: String, name: String): UIO[Option[String]] =
      seen.get.map(_.findLast(_.url.path.toString == path).flatMap(_.rawHeader(name)))

  private def parseForm(body: String): Map[String, String] =
    body
      .split('&')
      .iterator
      .filter(_.nonEmpty)
      .map: field =>
        val separator = field.indexOf('=')
        java.net.URLDecoder.decode(field.substring(0, separator), "UTF-8") ->
          java.net.URLDecoder.decode(field.substring(separator + 1), "UTF-8")
      .toMap

  /** `steps` is the conversation the stub will render, one page per entry; each submit consumes
    * one and redirects back to `/challenge`, and the last one redirects to the code.
    */
  def routes(
      steps: List[String],
      recorder: Recorder,
      remaining: Ref[List[String]],
      liveCookie: Ref[String],
      ssoLive: Ref[Boolean],
      pendingLogin: Ref[Boolean],
      silentReauthorize: Boolean,
      codeRedirectTo: String,
  ): Routes[Any, Nothing] =
    val challengeRedirect = Response
      .seeOther(URL.decode("/challenge").toOption.get)
      .addCookie(Cookie.Response("SSO_CONVERSATION", conversation))

    // A silent reauthorization (design doc §7.4): the SUT recognized the SSO_SESSION already
    // satisfies the request and answers straight with the code, no conversation started.
    val silentReauthorizeRedirect = Response.seeOther(URL.decode(codeRedirectTo).toOption.get)

    def advance: UIO[Response] =
      remaining.modify:
        case _ :: Nil | Nil => (true, Nil)
        case _ :: rest => (false, rest)
      .map: finished =>
        if finished then
          Response
            .seeOther(URL.decode(codeRedirectTo).toOption.get)
            .addCookie(Cookie.Response("SSO_SESSION", ssoSession))
        else challengeRedirect

    val handled = Routes(
      Method.GET / "authorize" -> handler((_: Request) =>
        if silentReauthorize then ZIO.succeed(silentReauthorizeRedirect)
        else remaining.set(steps).as(challengeRedirect),
      ),
      Method.GET / "challenge" -> handler: (_: Request) =>
        remaining.get.map(pending => Response.text(page(pending.headOption.getOrElse("credential")))),
      Method.POST / "challenge" / "phone" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "otp" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "password" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "set-password" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "login-password" -> handler((_: Request) => advance),
      Method.GET / "challenge" / "passkey" / "options" -> handler((_: Request) => ZIO.succeed(Response.json(passkeyOptions("Y2hhbGxlbmdl")))),
      Method.POST / "challenge" / "passkey" -> handler((_: Request) => advance),
      Method.POST / "token" -> handler((_: Request) => ZIO.succeed(Response.json(tokenBody))),
      // Auth's RP-initiated logout, both branches `LogoutController` has: an `id_token_hint`
      // identifies the session and logs it out on the spot (the mobile path), a bare cookie only
      // gets the confirmation page -- and the session survives until that page is submitted.
      Method.GET / "logout" -> handler: (request: Request) =>
        if request.url.queryParams.getAll("id_token_hint").nonEmpty then ssoLive.set(false).as(Response.ok)
        // Auth identifies the session to confirm away by the cookie alone here. Without it there
        // is no session to name, so it renders the signed-out page -- which carries no token, and
        // so cannot be submitted. The page is a 200 either way, which is why the driver has to
        // read the token rather than the status.
        else if !request.cookie("SSO_SESSION").map(_.content).contains(ssoSession) then ZIO.succeed(Response.ok)
        else
          ssoLive.get.map: live =>
            if live then Response.text(logoutConfirmPage) else Response.ok
      ,
      // The submission. The token is bound to the parameters, so one that comes back without
      // them is a 403 and the session stays live -- which is what auth does, and what makes a
      // driver that dropped them fail loudly instead of reporting a logout that did not happen.
      Method.POST / "logout" -> handler: (request: Request) =>
        request.body.asString.orDie.flatMap: body =>
          val form = parseForm(body)
          if form.get("csrf_token").contains(logoutCsrf) && form.get("post_logout_redirect_uri").contains(postLogoutRedirect) then
            ssoLive.set(false).as(Response.seeOther(URL.decode(postLogoutRedirect).toOption.get))
          else ZIO.succeed(Response.status(Status.Forbidden))
      ,
      // §8.4 hop 1: edge minted the PKCE pair and the state, recorded the pending login, and
      // hands the browser on to auth. A preset it does not know is a 404, as `EdgeController`'s
      // `PresetNotFound` branch answers.
      Method.GET / "login" / string("presetId") -> handler: (presetId: String, request: Request) =>
        if presetId != preset then ZIO.succeed(Response.notFound)
        else
          val forwarded = request.url.queryParams.getAll("acr_values").headOption
          val target = URL.decode(authorizeUrl).toOption.get
          ZIO.succeed(Response.seeOther(forwarded.fold(target)(values => target.addQueryParam("acr_values", values))))
      ,
      // §8.4's last hop. Edge exchanges the code itself and answers the cookie; a state it has
      // no pending login for is a 400 (`AuthConversationNotFound`).
      Method.GET / "complete" -> handler: (request: Request) =>
        val state = request.url.queryParams.getAll("state").headOption
        val returned = request.url.queryParams.getAll("code").headOption
        val refused = request.url.queryParams.getAll("error").headOption
        if !state.contains(edgeState) then ZIO.succeed(Response.badRequest)
        // The refusal branch: the pending login is consumed and the app is told, which is the
        // whole point of the hop -- a second call for the same state is the 400 below.
        else if refused.isDefined then
          pendingLogin.getAndSet(false).map: present =>
            if present then Response.seeOther(URL.decode(postLoginRedirect).toOption.get) else Response.badRequest
        else if returned.isEmpty then ZIO.succeed(Response.badRequest)
        else
          // A completed login is a live session again, so the proxy honours this value from here
          // on -- otherwise a renewal after a dead cookie would hand back one the stub rejects.
          liveCookie.set(edgeSession).as(
            Response
              .seeOther(URL.decode(postLoginRedirect).toOption.get)
              .addCookie(Cookie.Response("EDGE_SESSION", edgeSession, maxAge = Some(edgeCookieTtl))),
          )
      ,
      Method.GET / "logout" / "frontchannel" -> handler: (_: Request) =>
        ZIO.succeed(Response.ok.addCookie(Cookie.Response("EDGE_SESSION", "", maxAge = Some(Duration.Zero)))),
      // Edge hands the browser to auth, appending the preset's post-logout URI and no
      // `id_token_hint` -- edge keeps the id token, which is why the web path always confirms.
      Method.GET / "logout" / string("presetId") -> handler: (_: String, _: Request) =>
        ZIO.succeed(Response.seeOther(URL.decode(authLogoutUrl).toOption.get)),
      // §8.6 through the proxy. Rotates the cookie on every call, and honours only the value it
      // last issued: edge's cookie *is* the access token (`EdgeSessionCookie`), it rotates only
      // once that token has expired, and the refresh behind a superseded one is already spent --
      // so replaying one can only end in `Reauthenticate`, a 401 that also clears the cookie. A
      // stub that accepted any value would let a driver that never adopted the rotation pass.
      Method.GET / "resources" / trailing -> handler: (_: Path, request: Request) =>
        liveCookie.get.flatMap: live =>
          if !request.cookie("EDGE_SESSION").map(_.content).contains(live) then
            ZIO.succeed(
              Response
                .status(Status.Unauthorized)
                .addCookie(Cookie.Response("EDGE_SESSION", "", maxAge = Some(Duration.Zero))),
            )
          else
            liveCookie.set(rotatedEdgeSession).as(
              Response
                .json("""{"ok":true}""")
                .addCookie(Cookie.Response("EDGE_SESSION", rotatedEdgeSession, maxAge = Some(edgeCookieTtl))),
            ),
    )

    // Recording is a transform over every handler, the not-found one included, so a request to
    // a path the stub does not serve is still visible to the assertions.
    handled.transform(_.contramapZIO(request => recorder.seen.update(_ :+ request).as(request)))

  def make(steps: List[String], silentReauthorize: Boolean = false): ZIO[Any, Nothing, (Recorder, Routes[Any, Nothing])] =
    started(steps, silentReauthorize, codeRedirect)

  /** The same stub with auth ending its conversation at edge's `/complete` instead of at a
    * client redirect URI -- which is the only difference §8.4 makes to auth's half of it.
    */
  def makeWeb(steps: List[String]): ZIO[Any, Nothing, (Recorder, Routes[Any, Nothing])] =
    started(steps, false, edgeCodeRedirect)

  /** The same stub with auth refusing the authorization at the end of the conversation. */
  def makeWebRefused(steps: List[String]): ZIO[Any, Nothing, (Recorder, Routes[Any, Nothing])] =
    started(steps, false, edgeErrorRedirect)

  private def started(
      steps: List[String],
      silentReauthorize: Boolean,
      codeRedirectTo: String,
  ): ZIO[Any, Nothing, (Recorder, Routes[Any, Nothing])] =
    for
      seen <- Ref.make(Vector.empty[Request])
      remaining <- Ref.make(steps)
      liveCookie <- Ref.make(edgeSession)
      ssoLive <- Ref.make(true)
      pendingLogin <- Ref.make(true)
      recorder = Recorder(seen, ssoLive, pendingLogin)
    yield (recorder, routes(steps, recorder, remaining, liveCookie, ssoLive, pendingLogin, silentReauthorize, codeRedirectTo))
