package versola.e2e.flows.cells

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Header, Method, QueryParams, Request, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Two cells behind one central and one edge (versolauth/versola#547, #604): what each cell issues names it, the
  * directory in central keeps one user for an address whichever cell is asked (#1041), and the staged edge, told the
  * cells, serves a sign-in and its calls through the cell table (#559).
  *
  * Not here, because nothing in front of the cells routes a browser to the second one yet: a sign-in that reaches cell
  * 2 through the edge, and the handoff of an address held by another cell. Cell 2 is reached by its own address.
  */
object CellStandSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client & CellStand]:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private val UpstreamPort = 9116

  private val config = EdgeFixture.Config(
    resourceId = "e2e-cell-stand",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(
        name = "items",
        method = "GET",
        path = "/items",
        permitted = true,
        fetchUserInfo = false,
        allow = None,
        inject = Nil,
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
      // The endpoint that makes the edge show the session's token to auth: with cells registered, to the cell the
      // token's header names.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        permitted = true,
        fetchUserInfo = true,
        allow = None,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
    ),
    scopes = Set("openid", "email", "offline_access"),
    presetScope = Set("openid", "offline_access"),
    postLogoutRedirectUri = None,
    cookiePath = None,
    awaitProxyReady = true,
    privateKeyJwt = false,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    mutualTls = false,
    nativeApp = false,
    issuedCertificate = false,
    enrolledCertificate = false,
    groupCertificate = None,
  )

  // The fixture first and the stand after it: the stand's registration of the cells is what the edge is told last.
  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client & CellStand] =
    val base = E2EConfig.live ++ Client.default
    val clients = base >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    val fixture = (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)
    base ++ ((base ++ fixture) >+> CellStand.layer)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock, TestAspect.sequential)

  private val stand = ZIO.service[CellStand]

  /** A second edge of the staged build with the issuer's listener on (`APORT`), started once the cells are registered:
    * it reads them when it starts. The staged edge is started without the listener. */
  private val FrontPorts = Map("PORT" -> "9061", "DPORT" -> "9062", "MPORT" -> "9063", "APORT" -> "9064")
  private val FrontUrl = "http://localhost:9064"

  /** The issuer as a browser reaches it through the front: the same calls as to a cell, at the front's address. */
  private def startFront: ZIO[Scope & Client & E2EConfig, Throwable, CellStand.Cell] =
    for
      config <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
      running <- ExtraService.start(config.edgeLauncher, config.edgeEnvConf, "", FrontPorts)
      _ <- ExtraService.awaitReady(running)
    yield CellStand.Cell("front", OAuthClient(client, config.copy(authUrl = FrontUrl)), FrontUrl, FrontUrl)

  private def segment(jwt: String, index: Int): Task[Json.Obj] =
    for
      text <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(jwt.split('.')(index)), "UTF-8"))
      json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json

  private def cellHeader(accessToken: String): Task[Option[String]] = segment(accessToken, 0).map(_.str("cell"))

  private def subject(accessToken: String): Task[Option[String]] = segment(accessToken, 1).map(_.str("sub"))

  /** What a registration by email at `cell` ends in. */
  private case class Registered(code: String, cookie: Option[String], tokens: TokenResult.Success)

  /** The email-and-code conversation of `setup` at `cell` up to the code step's answer. */
  private def submitCode(cell: CellStand.Cell, setup: Flows.Setup, email: String): Task[(AuthorizeResult, SubmitResult)] =
    for
      authorize <- cell.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- cell.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- cell.auth.submitEmail(cookie, email, credential.csrf)
      otp <- cell.auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submitted <- cell.auth.submitOtp(cookie, fixedOtp, otp.csrf)
    yield (authorize, submitted)

  private def register(cell: CellStand.Cell, setup: Flows.Setup, email: String): Task[Registered] =
    for
      (authorize, submitted) <- submitCode(cell, setup, email)
      code <- submitted.assertRedirect
      tokens <- cell.auth.token(
        code,
        authorize.verifier,
        clientId = Some(setup.clientId),
        clientSecret = Some(setup.clientSecret),
        redirectUri = Some(setup.redirectUri),
      ).success
    yield Registered(code, authorize.conversationCookie, tokens)

  def spec = suite("Two cells")(
    test("what a cell issues names that cell, and a value of one cell is nothing to the other") {
      for
        s <- stand
        setup <- s.nextRegistration
        registered <- register(s.second, setup, setup.email.get)
        prefix = s"v1.${s.second.id}."
        cell <- cellHeader(registered.tokens.accessToken)
        userinfo <- s.second.auth.userinfo(registered.tokens.accessToken).success
        sub <- subject(registered.tokens.accessToken)
        // A second sign-in of the same user at cell 2, whose code is shown to cell 1: the other cell has its own
        // database, so the code is one it never issued.
        (authorize, submitted) <- submitCode(s.second, setup, setup.email.get)
        code <- submitted.assertRedirect
        elsewhere <- s.first.auth.token(
          code,
          authorize.verifier,
          clientId = Some(setup.clientId),
          clientSecret = Some(setup.clientSecret),
          redirectUri = Some(setup.redirectUri),
        )
      yield assertTrue(registered.code.startsWith(prefix)).label(s"the code of cell 2 starts with $prefix, got ${registered.code}") &&
        assertTrue(registered.cookie.exists(_.startsWith(prefix))).label("the conversation cookie of cell 2 names it") &&
        assertTrue(cell.contains(s.second.id)).label(s"the access token's header names cell 2, got $cell") &&
        assertTrue(sub.contains(userinfo.sub.toString)).label("cell 2 answers userinfo for its user") &&
        assertTrue(elsewhere.response.status == Status.BadRequest).label(s"cell 1 does not take a code of cell 2: ${elsewhere.response.status}")
    },
    test("an address a user of cell 2 holds does not make a second account in cell 1, and central finds the one user") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        setup <- s.nextRegistration
        email = setup.email.get
        registered <- register(s.second, setup, email)
        sub <- subject(registered.tokens.accessToken)
        // The same address at cell 1: the directory answers that cell 2 holds its user. Until the sign-in is handed
        // to that cell (Phase 2 step 5) cell 1 refuses it, and creates nothing.
        (_, atOther) <- submitCode(s.first, setup, email)
        found <- central.get("/users", "email" -> email).flatMap(_.items("users"))
        // Signing in again at cell 2 is the same user: the claim answered the same id.
        again <- register(s.second, setup, email)
        subAgain <- subject(again.tokens.accessToken)
      yield assertTrue(atOther.response.status != Status.SeeOther).label(s"cell 1 must not finish the sign-in, got ${atOther.response.status}") &&
        assertTrue(
          found.flatMap(_.str("id")) == Chunk.fromIterable(sub),
        ).label(s"central finds one user for the address, the one of cell 2: $found") &&
        assertTrue(subAgain == sub).label("a second sign-in at cell 2 is the same user")
    },
    test("an address nobody holds becomes a user of the cell that took the registration") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        one <- s.nextRegistration
        two <- s.nextRegistration
        atFirst <- register(s.first, one, one.email.get)
        atSecond <- register(s.second, two, two.email.get)
        firstCell <- cellHeader(atFirst.tokens.accessToken)
        secondCell <- cellHeader(atSecond.tokens.accessToken)
        firstSub <- subject(atFirst.tokens.accessToken)
        secondSub <- subject(atSecond.tokens.accessToken)
        foundFirst <- central.get("/users", "email" -> one.email.get).flatMap(_.items("users"))
        foundSecond <- central.get("/users", "email" -> two.email.get).flatMap(_.items("users"))
      yield assertTrue(firstCell.contains(s.first.id), secondCell.contains(s.second.id)) &&
        assertTrue(foundFirst.flatMap(_.str("id")) == Chunk.fromIterable(firstSub)).label("central's directory has the user of cell 1") &&
        assertTrue(foundSecond.flatMap(_.str("id")) == Chunk.fromIterable(secondSub)).label("and the user of cell 2")
    },
    // Phase 2 step 3: central's own directory says which cell holds a user, and its calls about the user go there.
    test("central reads a user of cell 2 from cell 2 and deletes it there, which frees the address") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        setup <- s.nextRegistration
        email = setup.email.get
        registered <- register(s.second, setup, email)
        sub <- subject(registered.tokens.accessToken)
        id <- ZIO.fromOption(sub).orElseFail(RuntimeException("the token has no sub"))
        found <- central.get("/users", "id" -> id).flatMap(_.items("users"))
        deleted <- central.delete("/users", "id" -> id)
        after <- central.get("/users", "email" -> email).flatMap(_.items("users"))
        // The account is gone from cell 2 and its address is nobody's: the same address registers as a new user.
        again <- register(s.second, setup, email)
        subAgain <- subject(again.tokens.accessToken)
      yield assertTrue(found.flatMap(_.str("email")) == Chunk(email)).label(s"central shows the address cell 2 holds: $found") &&
        assertTrue(deleted.status.isSuccess).label(s"central deletes the user: ${deleted.status} ${deleted.body}") &&
        assertTrue(after.isEmpty).label("the directory no longer has the address") &&
        assertTrue(subAgain.nonEmpty && subAgain != sub).label("the address registers again as another user")
    },
    // Phase 2 step 4 (versolauth/versola#1044): the issuer behind edge.
    test("through the issuer's listener a new sign-in is placed and finished at one cell, and a returning browser goes to the cell of its session") {
      ZIO.scoped:
        for
          s <- stand
          client <- ZIO.service[Client]
          front <- startFront
          // A new sign-in, every request of it through the front: placed in the open cell, continued there by the
          // conversation cookie, its code exchanged there by the code's key.
          fresh <- s.nextRegistration
          viaFront <- register(front, fresh, fresh.email.get)
          placedCell <- cellHeader(viaFront.tokens.accessToken)
          // A user of cell 2, signed in at cell 2 itself: the browser now holds cell 2's session cookie.
          returning <- s.nextRegistration
          (_, atHome) <- submitCode(s.second, returning, returning.email.get)
          session <- ZIO.fromOption(atHome.sessionCookie).orElseFail(RuntimeException("cell 2 set no session cookie"))
          sub <- atHome.assertRedirect.flatMap(code => ZIO.succeed(code))
          // The same browser comes back through the front: the session cookie's key sends it to cell 2, which knows
          // the session and answers with a code of its own, and the code's key sends the exchange there too.
          (verifier, challenge) = PkceHelper.generate()
          authorizeUrl <- ZIO.fromEither(URL.decode(s"$FrontUrl/authorize")).mapError(RuntimeException(_))
          resumed <- client.batched(
            Request.get(
              authorizeUrl.addQueryParams(
                List(
                  "response_type" -> "code",
                  "client_id" -> returning.clientId,
                  "redirect_uri" -> returning.redirectUri,
                  "scope" -> "openid",
                  "state" -> "s",
                  "code_challenge" -> challenge,
                  "code_challenge_method" -> "S256",
                ).map((name, value) => QueryParams(name -> value)).reduce(_ ++ _),
              ),
            ).addHeader(Header.Custom("Cookie", s"SSO_SESSION=$session")),
          )
          location = resumed.headers.get("Location").getOrElse("")
          resumedCode <- ZIO.fromOption(URL.decode(location).toOption.flatMap(_.queryParams.getAll("code").headOption))
            .orElseFail(RuntimeException(s"the front did not answer with a code: ${resumed.status} $location"))
          tokens <- front.auth.token(
            resumedCode,
            verifier,
            clientId = Some(returning.clientId),
            clientSecret = Some(returning.clientSecret),
            redirectUri = Some(returning.redirectUri),
          ).success
          resumedCell <- cellHeader(tokens.accessToken)
          internal <- client.batched(Request.get(URL.decode(s"$FrontUrl/users/claims").toOption.get))
        yield assertTrue(viaFront.code.startsWith(s"v1.${s.first.id}."), placedCell.contains(s.first.id))
          .label(s"a new sign-in through the front is placed in the open cell: ${viaFront.code}") &&
          assertTrue(session.startsWith(s"v1.${s.second.id}.")).label(s"cell 2's session cookie names it: $session") &&
          assertTrue(resumedCode.startsWith(s"v1.${s.second.id}."), resumedCell.contains(s.second.id))
            .label(s"the returning browser was sent to cell 2: $resumedCode") &&
          assertTrue(internal.status == Status.NotFound).label("auth's internal routes are not served through the front")
    },
    test("with the cells registered the staged edge signs a user in and makes its calls at the cell the values name") {
      for
        s <- stand
        auth <- ZIO.service[OAuthClient]
        edge <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        // The sign-in's code carries the key of cell 1, the only open cell, so the exchange is routed by it.
        session <- edge.browserLogin(auth, f.presetId, f.login, f.password)
        cell <- cellHeader(session.accessToken)
        items <- edge.proxy(Method.GET, f.resourceId, "/items", session.auth)
        // /userinfo at the cell of the token's header.
        profile <- edge.proxy(Method.GET, f.resourceId, "/profile", session.auth)
      yield assertTrue(cell.contains(s.first.id)).label(s"the session's token is of cell 1, got $cell") &&
        assertTrue(items.status == Status.Ok).label(s"the edge proxies for the session: ${items.status}") &&
        assertTrue(profile.status == Status.Ok).label(s"the edge asked userinfo at the token's cell: ${profile.status} ${profile.body}")
    },
  )
