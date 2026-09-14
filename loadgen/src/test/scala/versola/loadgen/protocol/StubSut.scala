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

  def page(step: String): String =
    """<!DOCTYPE html><html><head><meta name="versola-step" content="""" + step + """">""" +
      """<script>window.__VERSOLA_FORM__ = {"csrf":"""" + csrf + """"};</script></head><body></body></html>"""

  def passkeyOptions(challenge: String): String =
    """{"publicKey":{"challenge":"""" + challenge + """","rpId":"""" + rpId + """","userVerification":"required"}}"""

  def creationOptions(challenge: String): String =
    """{"publicKey":{"challenge":"""" + challenge + """","rp":{"id":"""" + rpId + """"}}}"""

  def codeRedirect: String = redirectUri + "?code=" + code + "&state=whatever"

  /** Every request the stub saw, in order, as `METHOD path`. */
  final case class Recorder(seen: Ref[Vector[Request]]):
    def paths: UIO[Vector[String]] = seen.get.map(_.map(request => request.method.name + " " + request.url.path.toString))

    def formOf(path: String): UIO[Option[Map[String, String]]] =
      seen.get.flatMap: requests =>
        requests.findLast(_.url.path.toString == path) match
          case None => ZIO.none
          case Some(request) => request.body.asString.map(body => Some(parseForm(body))).orDie

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
      Method.GET / "logout" -> handler((_: Request) => ZIO.succeed(Response.ok)),
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
        if !state.contains(edgeState) || returned.isEmpty then ZIO.succeed(Response.badRequest)
        else
          ZIO.succeed(
            Response
              .seeOther(URL.decode(postLoginRedirect).toOption.get)
              .addCookie(Cookie.Response("EDGE_SESSION", edgeSession, maxAge = Some(edgeCookieTtl))),
          )
      ,
      Method.GET / "logout" / "frontchannel" -> handler: (_: Request) =>
        ZIO.succeed(Response.ok.addCookie(Cookie.Response("EDGE_SESSION", "", maxAge = Some(Duration.Zero)))),
      Method.GET / "logout" / string("presetId") -> handler: (_: String, _: Request) =>
        ZIO.succeed(Response.seeOther(URL.decode(authUrl + "/logout").toOption.get)),
      // §8.6 through the proxy. Rotates the cookie on every call, which is the behaviour the
      // driver has to adopt or lose the session (§8.4).
      Method.GET / "resources" / trailing -> handler: (_: Path, _: Request) =>
        ZIO.succeed(
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

  private def started(
      steps: List[String],
      silentReauthorize: Boolean,
      codeRedirectTo: String,
  ): ZIO[Any, Nothing, (Recorder, Routes[Any, Nothing])] =
    for
      seen <- Ref.make(Vector.empty[Request])
      remaining <- Ref.make(steps)
      recorder = Recorder(seen)
    yield (recorder, routes(steps, recorder, remaining, silentReauthorize, codeRedirectTo))
