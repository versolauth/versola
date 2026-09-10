package versola.e2e.flows.edge

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Header, Method, Status, URL}
import zio.test.*

/** The edge's own web login: the `/login/{presetId}` → OP → `/complete` round trip that turns
  * a first-party navigation into an `EDGE_SESSION` cookie.
  *
  * None of this is visible to a client driving the OAuth flow itself — the edge mints the PKCE
  * verifier and the state, keeps both server-side, and the browser never sees a token.
  */
object EdgeWebLoginSpec extends EdgeSpec(
      EdgeFixture.Config(
        resourceId = "e2e-edge-login",
        resourceUri = "http://localhost:9004",
        endpoints = List(EdgeFixture.Endpoint(name = "liveness", path = "/liveness")),
      ),
    ):

  def spec = suite("Edge web login")(
    test("/login redirects to the OP's authorization endpoint") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        location = started.header(Header.Location).map(_.url.encode).getOrElse("")
      yield assertTrue(started.status == Status.SeeOther) &&
        assertTrue(location.contains("/authorize")).label(s"must hand off to the OP, got: $location")
    },
    test("the authorization request names the preset's client and redirect URI") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("client_id").contains(f.clientId)) &&
        assertTrue(url.queryParams.getAll("redirect_uri").contains(edgeApi.completeUri))
          .label("the OP must be told to come back to the edge, not to the app")
    },
    test("the authorization request carries a PKCE challenge the edge keeps to itself") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("code_challenge_method").contains("S256")) &&
        assertTrue(url.queryParams.getAll("code_challenge").headOption.exists(_.nonEmpty)) &&
        assertTrue(url.queryParams.getAll("code_verifier").isEmpty)
          .label("the verifier stays server-side; a browser must never receive it")
    },
    test("each /login call mints a fresh state") {
      for
        edgeApi <- edge
        f <- fixture
        first <- edgeApi.login(f.presetId).flatMap(stateOf)
        second <- edgeApi.login(f.presetId).flatMap(stateOf)
      yield assertTrue(first.nonEmpty && second.nonEmpty) &&
        assertTrue(first != second).label("a reused state would let one login's code complete another's")
    },
    test("an unknown preset answers 404") {
      for
        edgeApi <- edge
        started <- edgeApi.login("no-such-preset")
      yield assertTrue(started.status == Status.NotFound)
    },
    test("a whitelisted parameter is passed through to the OP") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId, "prompt" -> "login")
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("prompt").contains("login"))
    },
    test("login_hint and ui_locales are passed through to the OP") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId, "login_hint" -> f.login, "ui_locales" -> "en")
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("login_hint").contains(f.login)) &&
        assertTrue(url.queryParams.getAll("ui_locales").contains("en"))
    },
    test("max_age and acr_values are passed through to the OP") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId, "max_age" -> "0", "acr_values" -> Acr.PasswordLevel)
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("max_age").contains("0")) &&
        assertTrue(url.queryParams.getAll("acr_values").contains(Acr.PasswordLevel))
    },
    test("a parameter outside the whitelist is dropped") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId, "redirect_uri" -> "http://attacker.test/steal")
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(url.queryParams.getAll("redirect_uri").contains(edgeApi.completeUri))
        .label("a caller-supplied redirect_uri must not reach the OP")
    },
    test("a full browser login sets the EDGE_SESSION cookie") {
      for
        session <- signIn
      yield assertTrue(session.cookie.nonEmpty) &&
        assertTrue(session.accessToken.split('.').length == 3)
          .label("the cookie must carry a JWT access token")
    },
    test("the session cookie names the preset it was established for") {
      for
        f <- fixture
        session <- signIn
      yield assertTrue(session.presetId == f.presetId)
        .label("the proxy reads the preset out of the cookie to refresh the right session")
    },
    test("the completed login redirects to the preset's post-login URI") {
      for
        f <- fixture
        session <- signIn
        location = session.response.header(Header.Location).map(_.url.encode).getOrElse("")
      yield assertTrue(session.response.status == Status.SeeOther) &&
        assertTrue(location.startsWith(f.postLoginRedirectUri))
    },
    test("the session cookie is HttpOnly, Secure and SameSite=Strict") {
      for
        session <- signIn
        cookie <- ZIO.fromOption(
          session.response.headers.getAll(Header.SetCookie)
            .collectFirst { case h if h.value.name == EdgeApi.sessionCookieName => h.value },
        ).orElseFail(RuntimeException("no EDGE_SESSION cookie on the completed login"))
      yield assertTrue(cookie.isHttpOnly).label("script must not be able to read the session") &&
        assertTrue(cookie.isSecure) &&
        assertTrue(cookie.sameSite.contains(zio.http.Cookie.SameSite.Strict))
          .label("SameSite=Strict is what keeps the cookie off cross-site requests")
    },
    test("two logins of the same user produce two distinct sessions") {
      for
        first <- signIn
        second <- signIn
      yield assertTrue(first.accessToken != second.accessToken)
        .label("each login must get its own token, not a shared one")
    },
    test("/complete with an unknown state is rejected") {
      for
        edgeApi <- edge
        result <- edgeApi.complete("code" -> "irrelevant", "state" -> "9f8c1b7a4e2d")
      yield assertTrue(result.status == Status.BadRequest)
        .label("the state is the only thing tying a code to a login the edge started")
    },
    test("/complete with neither code nor error is rejected") {
      for
        edgeApi <- edge
        result <- edgeApi.complete("state" -> "9f8c1b7a4e2d")
      yield assertTrue(result.status == Status.BadRequest)
    },
    test("/complete without a state is rejected") {
      for
        edgeApi <- edge
        result <- edgeApi.complete("code" -> "irrelevant")
      yield assertTrue(result.status == Status.BadRequest)
    },
    test("/complete carrying both a code and an error is rejected") {
      for
        edgeApi <- edge
        result <- edgeApi.complete("code" -> "irrelevant", "error" -> "access_denied", "state" -> "9f8c1b7a")
      yield assertTrue(result.status == Status.BadRequest)
        .label("a response cannot be both a success and a failure")
    },
    test("a state is single-use: the same code cannot complete twice") {
      for
        edgeApi <- edge
        authApi <- auth
        f <- fixture
        callback <- edgeApi.authorize(authApi, f.presetId, f.login, f.password)
        first <- edgeApi.complete("code" -> callback.code, "state" -> callback.state)
        second <- edgeApi.complete("code" -> callback.code, "state" -> callback.state)
      yield assertTrue(first.status == Status.SeeOther) &&
        assertTrue(second.status == Status.BadRequest)
          .label("the login record is consumed, so a replayed callback has nothing to match")
    },
    test("an OP error is reported to the app, not to the browser as a failure") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        state <- stateOf(started)
        result <- edgeApi.complete("error" -> "access_denied", "state" -> state)
        location = result.header(Header.Location).map(_.url.encode).getOrElse("")
      yield assertTrue(result.status == Status.SeeOther) &&
        assertTrue(location.startsWith(f.postLoginRedirectUri)) &&
        assertTrue(location.contains("error=access_denied"))
          .label("the app's own page has to render the failure, so the error travels there")
    },
    test("an OP error carries its description through to the app") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        state <- stateOf(started)
        result <- edgeApi.complete(
          "error" -> "invalid_scope",
          "error_description" -> "scope not allowed",
          "state" -> state,
        )
        location = result.header(Header.Location).map(_.url.encode).getOrElse("")
      yield assertTrue(location.contains("error_description")) &&
        assertTrue(location.contains("scope"))
    },
    test("an errored login consumes its state too") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        state <- stateOf(started)
        first <- edgeApi.complete("error" -> "access_denied", "state" -> state)
        second <- edgeApi.complete("error" -> "access_denied", "state" -> state)
      yield assertTrue(first.status == Status.SeeOther) &&
        assertTrue(second.status == Status.BadRequest)
    },
    test("an errored login sets no session cookie") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        state <- stateOf(started)
        result <- edgeApi.complete("error" -> "access_denied", "state" -> state)
      yield assertTrue(EdgeApi.sessionCookie(result).isEmpty)
    },
    test("a code from one login cannot be completed under another login's state") {
      for
        edgeApi <- edge
        authApi <- auth
        f <- fixture
        callback <- edgeApi.authorize(authApi, f.presetId, f.login, f.password)
        other <- edgeApi.login(f.presetId)
        otherState <- stateOf(other)
        result <- edgeApi.complete("code" -> callback.code, "state" -> otherState)
      yield assertTrue(result.status != Status.SeeOther)
        .label("the PKCE verifier stored under the other state cannot redeem this code")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

  private def stateOf(response: zio.http.Response): Task[String] =
    for
      url <- ZIO.fromEither(URL.decode(response.header(Header.Location).map(_.url.encode).getOrElse("")))
        .mapError(RuntimeException(_))
      state <- ZIO.fromOption(url.queryParams.getAll("state").headOption)
        .orElseFail(RuntimeException(s"no state in ${url.encode}"))
    yield state
