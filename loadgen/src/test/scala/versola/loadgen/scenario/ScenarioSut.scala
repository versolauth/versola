package versola.loadgen.scenario

import versola.loadgen.protocol.StubSut
import zio.*
import zio.http.*

/** `versola.loadgen.protocol.StubSut` with the three behaviours the scenario engine branches on,
  * which that one deliberately does not have: refresh rotation with reuse detection, a step-up
  * demand on an ACR-gated action, and a `403` on an action a role does not cover.
  *
  * It is a separate stub rather than more flags on `StubSut`, because that one exists to pin the
  * *hop sequences* of §8 -- it answers `/token` with a constant body, which is exactly right for
  * what it tests and cannot express "this refresh token has already been exchanged". Its
  * constants are reused so the two stubs describe the same SUT. Nothing here validates a
  * credential either: what this models is the SUT's *state machine*, because that is what §7.4's
  * discipline is stated against.
  */
object ScenarioSut:
  export StubSut.{authUrl, edgeUrl, origin, redirectUri, csrf, ssoSession, code, edgeState, requestTimeout, registry, page}
  export StubSut.{preset, edgeCookieTtl, postLoginRedirect, postLogoutRedirect, logoutCsrf, logoutConfirmPage, authLogoutUrl}
  export StubSut.{authorizeUrl, edgeCodeRedirect, codeRedirect, passkeyOptions}

  /** The ACR the gated action demands, as `CampaignBlueprint` names L2. */
  val stepUpAcr = "otp-level"

  final case class State(
      seen: Ref[Vector[String]],
      spentRefresh: Ref[Set[String]],
      refused: Ref[Int],
      live: Ref[String],
  ):
    def paths: UIO[Vector[String]] = seen.get

    /** The `EDGE_SESSION` the SUT last issued, which is the only value it still honours. */
    def liveCookie: UIO[String] = live.get

    /** How many refreshes the SUT refused. A campaign's own `refresh_rejected` staying at 0 only
      * means something alongside this: it also counts the ones the driver never noticed.
      */
    def refusedRefreshes: UIO[Int] = refused.get

  def make(
      steps: List[String],
      stepUpPath: String,
      forbiddenPaths: Set[String],
      codeRedirectTo: String,
  ): UIO[(State, Routes[Any, Nothing])] =
    for
      seen <- Ref.make(Vector.empty[String])
      remaining <- Ref.make(steps)
      spent <- Ref.make(Set.empty[String])
      acrOfToken <- Ref.make(Map.empty[String, String])
      pendingAcr <- Ref.make(Option.empty[String])
      cookieAcr <- Ref.make(Option.empty[String])
      liveCookie <- Ref.make("edge-session-0")
      counter <- Ref.make(0)
      refused <- Ref.make(0)
      state = State(seen, spent, refused, liveCookie)
      built = routes(state, steps, remaining, acrOfToken, pendingAcr, cookieAcr, liveCookie, counter, stepUpPath, forbiddenPaths, codeRedirectTo)
    yield (state, built)

  def mobile(steps: List[String], stepUpPath: String, forbiddenPaths: Set[String] = Set.empty): UIO[(State, Routes[Any, Nothing])] =
    make(steps, stepUpPath, forbiddenPaths, codeRedirect)

  def web(steps: List[String], stepUpPath: String, forbiddenPaths: Set[String] = Set.empty): UIO[(State, Routes[Any, Nothing])] =
    make(steps, stepUpPath, forbiddenPaths, edgeCodeRedirect)

  private def routes(
      state: State,
      configuredSteps: List[String],
      remaining: Ref[List[String]],
      acrOfToken: Ref[Map[String, String]],
      pendingAcr: Ref[Option[String]],
      cookieAcr: Ref[Option[String]],
      liveCookie: Ref[String],
      counter: Ref[Int],
      stepUpPath: String,
      forbiddenPaths: Set[String],
      codeRedirectTo: String,
  ): Routes[Any, Nothing] =
    val challengeRedirect = Response
      .seeOther(URL.decode("/challenge").toOption.get)
      .addCookie(Cookie.Response("SSO_CONVERSATION", StubSut.conversation))

    /** A step-up asks only for the factor the requested assurance level is missing, which is one
      * page and not the whole conversation again -- folding the two together would hide a driver
      * that re-ran a full login where the SUT asked for a single re-authentication.
      */
    def begin(acrValues: Option[String]): UIO[Response] =
      pendingAcr.set(acrValues) *>
        remaining.set(if acrValues.isDefined then List("otp") else configuredSteps).as(challengeRedirect)

    def advance: UIO[Response] =
      remaining
        .modify:
          case _ :: Nil | Nil => (true, Nil)
          case _ :: rest => (false, rest)
        .map: finished =>
          if finished then
            Response.seeOther(URL.decode(codeRedirectTo).toOption.get).addCookie(Cookie.Response("SSO_SESSION", ssoSession))
          else challengeRedirect

    def mint: UIO[(String, String)] =
      counter.updateAndGet(_ + 1).map(n => (s"access-$n", s"refresh-$n"))

    def tokenBody(access: String, refresh: String): String =
      s"""{"access_token":"$access","token_type":"Bearer","expires_in":900,""" +
        s""""refresh_token":"$refresh","id_token":"id-$access","scope":"openid phone offline_access"}"""

    /** A rotated pair inherits the assurance level of the one it replaces: a refresh does not
      * re-authenticate anybody, so a session that stepped up stays stepped up across it.
      */
    def onRefresh(presented: String): UIO[Response] =
      state.spentRefresh.modify(spent => (spent.contains(presented), spent + presented)).flatMap:
        case true =>
          state.refused.update(_ + 1).as(Response.json("""{"error":"invalid_grant"}""").status(Status.BadRequest))
        case false =>
          for
            minted <- mint
            (access, refresh) = minted
            inherited <- acrOfToken.get.map(_.collectFirst { case (_, acr) => acr })
            _ <- ZIO.foreachDiscard(inherited)(acr => acrOfToken.update(_.updated(access, acr)))
          yield Response.json(tokenBody(access, refresh))

    def onToken(request: Request): UIO[Response] =
      request.body.asString.orDie.flatMap: body =>
        val form = parseForm(body)
        if form.get("grant_type").contains("refresh_token") then onRefresh(form.getOrElse("refresh_token", ""))
        else
          for
            acr <- pendingAcr.getAndSet(None)
            minted <- mint
            (access, refresh) = minted
            _ <- ZIO.foreachDiscard(acr)(value => acrOfToken.update(_.updated(access, value)))
            _ <- ZIO.foreachDiscard(acr)(value => cookieAcr.set(Some(value)))
          yield Response.json(tokenBody(access, refresh))

    def rotateCookie(cookie: Option[String]): UIO[Response] =
      cookie match
        case None => ZIO.succeed(Response.json("""{"ok":true}"""))
        case Some(_) =>
          counter.updateAndGet(_ + 1).flatMap: n =>
            val rotated = s"edge-session-$n"
            liveCookie
              .set(rotated)
              .as(Response.json("""{"ok":true}""").addCookie(Cookie.Response("EDGE_SESSION", rotated, maxAge = Some(edgeCookieTtl))))

    def onResource(request: Request, path: String): UIO[Response] =
      val bearer = request.rawHeader("Authorization").filter(_.startsWith("Bearer ")).map(_.drop("Bearer ".length))
      val cookie = request.cookie("EDGE_SESSION").map(_.content)
      for
        byToken <- acrOfToken.get
        byCookie <- cookieAcr.get
        acr = bearer.flatMap(byToken.get).orElse(cookie.flatMap(_ => byCookie))
        response <-
          if bearer.isEmpty && cookie.isEmpty then ZIO.succeed(Response.status(Status.Unauthorized))
          else if forbiddenPaths.contains(path) then ZIO.succeed(Response.status(Status.Forbidden))
          else if path == stepUpPath && !acr.contains(stepUpAcr) then
            ZIO.succeed(
              Response
                .status(Status.Unauthorized)
                .addHeader("WWW-Authenticate", s"""Bearer error="insufficient_user_authentication", acr_values="$stepUpAcr""""),
            )
          else rotateCookie(cookie)
      yield response

    val handled = Routes(
      Method.GET / "authorize" -> handler((request: Request) => begin(request.url.queryParams.getAll("acr_values").headOption)),
      Method.GET / "challenge" -> handler: (_: Request) =>
        remaining.get.map(pending => Response.text(page(pending.headOption.getOrElse("credential")))),
      Method.POST / "challenge" / "phone" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "otp" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "password" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "login-password" -> handler((_: Request) => advance),
      Method.POST / "challenge" / "passkey" -> handler((_: Request) => advance),
      Method.GET / "challenge" / "passkey" / "options" -> handler: (_: Request) =>
        ZIO.succeed(Response.json(passkeyOptions("Y2hhbGxlbmdl"))),
      Method.POST / "token" -> handler(onToken(_)),
      Method.GET / "logout" -> handler: (request: Request) =>
        if request.url.queryParams.getAll("id_token_hint").nonEmpty then ZIO.succeed(Response.ok)
        else ZIO.succeed(Response.text(logoutConfirmPage)),
      Method.POST / "logout" -> handler: (request: Request) =>
        request.body.asString.orDie.map: body =>
          val form = parseForm(body)
          if form.get("csrf_token").contains(logoutCsrf) then Response.seeOther(URL.decode(postLogoutRedirect).toOption.get)
          else Response.status(Status.Forbidden)
      ,
      Method.GET / "login" / string("presetId") -> handler: (_: String, request: Request) =>
        val forwarded = request.url.queryParams.getAll("acr_values").headOption
        val target = URL.decode(authorizeUrl).toOption.get
        ZIO.succeed(Response.seeOther(forwarded.fold(target)(values => target.addQueryParam("acr_values", values)))),
      Method.GET / "logout" / string("presetId") -> handler: (_: String, _: Request) =>
        ZIO.succeed(Response.seeOther(URL.decode(authLogoutUrl).toOption.get)),
      Method.GET / "logout" / "frontchannel" -> handler: (_: Request) =>
        ZIO.succeed(Response.ok.addCookie(Cookie.Response("EDGE_SESSION", "", maxAge = Some(Duration.Zero)))),
      Method.GET / "complete" -> handler: (request: Request) =>
        if request.url.queryParams.getAll("code").isEmpty then ZIO.succeed(Response.badRequest)
        else
          counter.updateAndGet(_ + 1).flatMap: n =>
            val issued = s"edge-session-$n"
            liveCookie
              .set(issued)
              .as(
                Response
                  .seeOther(URL.decode(postLoginRedirect).toOption.get)
                  .addCookie(Cookie.Response("EDGE_SESSION", issued, maxAge = Some(edgeCookieTtl))),
              )
      ,
      Method.ANY / "resources" / trailing -> handler: (rest: Path, request: Request) =>
        onResource(request, "/resources" + rest.addLeadingSlash.toString),
    )

    handled.transform:
      _.contramapZIO: request =>
        state.seen.update(_ :+ (request.method.name + " " + request.url.path.toString)).as(request)

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
