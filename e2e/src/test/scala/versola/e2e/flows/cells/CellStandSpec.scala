package versola.e2e.flows.cells

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Body, Client, Header, MediaType, Method, QueryParams, Request, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URLEncoder

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

  /** The user id edge would add for the operator who changes the antifraud settings. */
  private val Operator = "00000000-0000-0000-0000-0000000e2e05"

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

  /** The issuer's address as a browser has it once it is an edge's: the staged edge, which serves the pages of a
    * sign-in and sends each request to its cell. Only those pages are there; a code is exchanged at the cell. */
  private val front: ZIO[E2EConfig & Client, Nothing, CellStand.Cell] =
    for
      config <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
    yield CellStand.Cell("front", OAuthClient(client, config.copy(authUrl = config.edgeUrl)), config.edgeUrl, config.edgeUrl)

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
  private def submitCode(
      cell: CellStand.Cell,
      setup: Flows.Setup,
      email: String,
  ): RIO[CellStand & E2EConfig & Client, (AuthorizeResult, SubmitResult)] =
    ZIO.serviceWithZIO[CellStand]: s =>
      // An address nobody holds is registered in the cell central names for it (versolauth/versola#1052): for a user of
      // cell 2, cell 2 is the one cell that takes users while the address is typed.
      if cell.id == s.second.id then s.whileOnlySecondTakesUsers(codeSubmitted(cell, setup, email))
      else codeSubmitted(cell, setup, email)

  /** What a browser does with a redirect of the issuer: a GET at the address through edge, with the conversation cookie it
    * holds, when it holds one. */
  private def visited(location: String, cookie: Option[String]): ZIO[E2EConfig & Client, Throwable, Response] =
    for
      config <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}$location")).mapError(RuntimeException(_))
      request = Request.get(url)
      response <- client.batched(cookie.fold(request)(content => request.addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$content"))))
    yield response

  /** The conversation cookie a response sets, when it sets one. */
  private def conversationCookieOf(response: Response): Option[String] =
    response.headers.getAll(Header.SetCookie).collectFirst {
      case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
    }

  /** The address typed at `cell` on a new sign-in: the request, the cookie of its conversation, and the answer to the address. */
  private def typedAt(cell: CellStand.Cell, setup: Flows.Setup, email: String): Task[(AuthorizeResult, String, SubmitResult)] =
    for
      authorize <- cell.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      card <- cell.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      typed <- cell.auth.submitEmail(cookie, email, card.csrf)
    yield (authorize, cookie, typed)

  private def handedTo(cell: CellStand.Cell, answer: SubmitResult): Boolean =
    answer.location.contains(s"/challenge/handoff?handoff=v1.${cell.id}.")

  /** The conversation of `setup` at `cell` up to the code step's answer. An address nobody holds may be sent to another cell
    * than the one it was typed at, which a browser follows (versolauth/versola#1052): the code is then asked, and typed, there; and a
    * cell that takes no users hands the registration, once the code is verified, to one that does, which a browser follows as well.
    * What is returned is the last answer of the browser, which is the redirect with the code when the sign-in has finished. */
  private def codeSubmitted(
      cell: CellStand.Cell,
      setup: Flows.Setup,
      email: String,
  ): RIO[E2EConfig & Client, (AuthorizeResult, SubmitResult)] =
    for
      (authorize, cookie, typed) <- typedAt(cell, setup, email)
      browser <- front
      // Where the code is asked: here, or at the cell the address was sent to.
      (asked, held) <-
        if typed.location.contains("/challenge/handoff?handoff=") then
          visited(typed.location, None).flatMap: arrived =>
            ZIO.fromOption(conversationCookieOf(arrived)).orElseFail(RuntimeException(s"the handoff was not taken: ${arrived.status}"))
              .map(next => (browser.auth, next))
        else ZIO.succeed((cell.auth, cookie))
      otp <- asked.getChallenge(held).assertStep(ConversationStep.Otp)
      submitted <- asked.submitOtp(held, fixedOtp, otp.csrf)
      finished <-
        if submitted.location.contains("/challenge/registration?registration=") then
          visited(submitted.location, Some(held)).map(SubmitResult(_))
        else ZIO.succeed(submitted)
    yield (authorize, finished)

  private def register(cell: CellStand.Cell, setup: Flows.Setup, email: String): RIO[CellStand & E2EConfig & Client, Registered] =
    for
      s <- stand
      (authorize, submitted) <- submitCode(cell, setup, email)
      code <- submitted.assertRedirect
      // The code names the cell that issued it, which is the one that took the registration.
      issuer = if code.startsWith(s"v1.${s.second.id}.") then s.second else s.first
      tokens <- issuer.auth.token(
        code,
        authorize.verifier,
        clientId = Some(setup.clientId),
        clientSecret = Some(setup.clientSecret),
        redirectUri = Some(setup.redirectUri),
      ).success
    yield Registered(code, authorize.conversationCookie, tokens)

  /** A user of cell 2 who signs in with a login and a password. */
  private case class LoginUser(userId: java.util.UUID, sub: Option[String], login: String, password: String)

  /** Registers a user at cell 2 and gives it a login and a password through central, which sets both in the cell that
    * holds the user. The login travels by central's outbox, so a sign-in at cell 2 is repeated, within a bound, until
    * cell 2 knows the login. */
  private def loginUserOfSecond(s: CellStand): ZIO[OAuthClient & CentralApi & CellStand & E2EConfig & Client, Throwable, LoginUser] =
    loginUserOf(s, s.second)

  /** The same for a user of `cell`. */
  private def loginUserOf(s: CellStand, cell: CellStand.Cell): ZIO[OAuthClient & CentralApi & CellStand & E2EConfig & Client, Throwable, LoginUser] =
    for
      auth <- ZIO.service[OAuthClient]
      central <- ZIO.service[CentralApi]
      setup <- s.nextRegistration
      registered <- register(cell, setup, setup.email.get)
      sub <- subject(registered.tokens.accessToken)
      userId <- ZIO.fromOption(sub).mapBoth(_ => RuntimeException("the token has no sub"), java.util.UUID.fromString)
      login <- CentralApi.login("cell")
      password = "Cell-Stand-Pass-2!"
      patched <- central.patch("/users", Json.Obj("id" -> Json.Str(userId.toString), "login" -> Json.Str(login)))
      _ <- ZIO.fail(RuntimeException(s"central refused the login: ${patched.status} ${patched.body}")).unless(patched.status.isSuccess)
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
      user = LoginUser(userId, sub, login, password)
      _ <- signInAt(cell, s.loginClient, user).retry(Schedule.spaced(200.millis) && Schedule.recurs(50))
    yield user

  /** A sign-in with the login and password at `cell` itself, to its tokens. */
  private def signInAt(
      cell: CellStand.Cell,
      client: CellStand.RegisteredClient,
      user: LoginUser,
      scope: String = "openid",
  ): Task[TokenResult.Success] =
    for
      authorize <- cell.auth.authorize(scope = scope, clientId = Some(client.clientId), redirectUri = Some(client.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      card <- cell.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- cell.auth.submitLoginPassword(cookie, user.login, user.password, card.csrf).flatMap(_.assertRedirect)
      tokens <- cell.auth.token(
        code,
        authorize.verifier,
        clientId = Some(client.clientId),
        clientSecret = Some(client.clientSecret),
        redirectUri = Some(client.redirectUri),
      ).success
    yield tokens

  /** What a browser does with the `307` a cell answers a submission with: the same POST, with the same form, at the
    * address it names, through edge; `cookie` is the conversation cookie the browser holds, when it holds one. */
  private def repeated(sentOn: SubmitResult, form: Map[String, String], cookie: Option[String]): ZIO[E2EConfig & Client, Throwable, SubmitResult] =
    for
      config <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
      url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${sentOn.location}")).mapError(RuntimeException(_))
      body = form.map((name, value) => s"${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}").mkString("&")
      request = Request.post(url, Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
      response <- client.batched(cookie.fold(request)(content => request.addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$content"))))
    yield SubmitResult(response)

  /** The first page of a sign-in with the login client through edge: placed in cell 1, on the credential card. */
  private def placed(front: CellStand.Cell, client: CellStand.RegisteredClient): Task[(AuthorizeResult, String, ChallengeResult)] =
    for
      authorize <- front.auth.authorize(clientId = Some(client.clientId), redirectUri = Some(client.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      card <- front.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
    yield (authorize, cookie, card)

  private def sentTo(cell: CellStand.Cell, result: SubmitResult): Boolean =
    result.response.status == Status.TemporaryRedirect && result.location.contains(s"/challenge/handoff?handoff=v1.${cell.id}.")

  /** Asks central to move the user to `cell` and waits, within a bound, until the move is done: central does it in the
    * background, a phase at a time, and the user's record names the cell and the move. */
  private def moved(central: CentralApi, userId: String, cell: String): Task[Json.Obj] =
    for
      started <- central.post("/users/move", Json.Obj("userId" -> Json.Str(userId), "cell" -> Json.Str(cell)))
      _ <- ZIO.fail(RuntimeException(s"central did not start the move: ${started.status} ${started.body}"))
        .unless(started.status == Status.Accepted)
      record <- central.get("/users", "id" -> userId).flatMap(_.items("users")).map(_.headOption)
        .repeat(
          Schedule.recurUntil[Option[Json.Obj]](_.exists(user =>
            user.str("cell").contains(cell) && user.get("move").flatMap(_.asObject).flatMap(_.str("phase")).contains("done"),
          )) && Schedule.spaced(500.millis) && Schedule.recurs(120),
        ).map(_._1)
      user <- ZIO.fromOption(record).orElseFail(RuntimeException("central lost the user"))
      _ <- ZIO.fail(RuntimeException(s"the move did not finish within a minute: ${user.toJson}"))
        .unless(user.get("move").flatMap(_.asObject).flatMap(_.str("phase")).contains("done"))
    yield user

  /** What the topology says of a cell. */
  private def cellOfTopology(central: CentralApi, id: String): Task[Json.Obj] =
    central.get("/configuration/topology").flatMap(_.items("cells")).flatMap: cells =>
      ZIO.fromOption(cells.find(_.str("id").contains(id))).orElseFail(RuntimeException(s"the topology has no cell $id"))

  private def number(of: Json.Obj, name: String): Option[Long] =
    of.get(name).flatMap(_.asNumber).map(_.value.longValue)

  private case class IdTokenSid(sid: String) derives JsonDecoder

  private case class EnrollmentStart(publicKeyOptions: String, ticket: String) derives JsonDecoder

  /** Enrolls a passkey for `user` at cell 2, with the session of a sign-in there. */
  private def enrollPasskeyAtSecond(s: CellStand, user: LoginUser): Task[TestAuthenticator.Credential] =
    for
      tokens <- signInAt(s.second, s.loginClient, user)
      idToken <- ZIO.fromOption(tokens.idToken).orElseFail(RuntimeException("the token response carries no id_token"))
      claims <- segment(idToken, 1)
      sid <- ZIO.fromOption(claims.str("sid")).orElseFail(RuntimeException("the id_token has no sid"))
      caller = AccountCaller(user.userId, s.loginClient.clientId, sid)
      started <- s.second.auth.startPasskeyEnrollment(caller)
      ceremony <- ZIO.fromEither(started.body.fromJson[EnrollmentStart])
        .mapError(error => RuntimeException(s"unexpected enrollment start [$error]: ${started.body}"))
      credential <- TestAuthenticator.create(ceremony.publicKeyOptions, Flows.passkeyOrigin)
      finished <- s.second.auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, "Cell stand passkey")
      _ <- ZIO.fail(RuntimeException(s"cell 2 did not enroll the passkey: ${finished.status} ${finished.body}")).unless(finished.status.isSuccess)
    yield credential

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
        // The same address at cell 1: the directory answers that cell 2 holds its user, so cell 1 sends no code and
        // creates nothing; it sends the browser to cell 2 (the next case follows it there).
        authorize <- s.first.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri)).assertChallengeRedirect
        credential <- s.first.auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        atOther <- s.first.auth.submitEmail(authorize.conversationCookie.get, email, credential.csrf)
        found <- central.get("/users", "email" -> email).flatMap(_.items("users"))
        // Signing in again at cell 2 is the same user: the claim answered the same id.
        again <- register(s.second, setup, email)
        subAgain <- subject(again.tokens.accessToken)
      yield assertTrue(atOther.location.contains(s"/challenge/handoff?handoff=v1.${s.second.id}."))
        .label(s"cell 1 does not go on with the sign-in but hands it to cell 2: ${atOther.response.status} ${atOther.location}") &&
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
    // The antifraud policy is central's, one for every cell: each cell copies it on a poll and enforces its copy.
    test("a change of the antifraud settings made in central reaches every cell, which the status of each cell says") {
      // The version of the settings each cell holds, by cell; a cell that did not answer has none.
      def versions(central: CentralApi): Task[Map[String, Option[Long]]] =
        central.get("/antifraud/status").flatMap(_.items("cells")).map: cells =>
          cells.flatMap: cell =>
            cell.str("cell").map: id =>
              id -> cell.get("status").collect { case status: Json.Obj => status }
                .flatMap(_.get("tuning")).collect { case tuning: Json.Obj => tuning }
                .flatMap(_.get("version")).collect { case Json.Num(version) => version.longValue }
          .toMap
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        read <- central.get("/antifraud/settings").flatMap(_.obj)
        settings = Json.Obj(read.fields.filterNot((name, _) => name == "version"))
        // The same settings again: nothing changes for the suite, and the version moves, as with every write.
        written <- central.asAdmin(Operator).put("/antifraud/settings", Json.Obj("settings" -> settings))
        version <- written.obj.map(_.get("version").collect { case Json.Num(version) => version.longValue })
        // Awaited: each instance asks central every `antifraud.tuning.refresh-interval` (5 s) and rereads its copy after.
        reached <- versions(central)
          .repeat(Schedule.spaced(500.millis) *> Schedule.recurUntil[Map[String, Option[Long]]](_.values.forall(_ == version)))
          .timeout(40.seconds)
          .someOrElseZIO(versions(central))
      yield assertTrue(written.status.isSuccess).label(s"central stores the settings: ${written.status} ${written.body}") &&
        assertTrue(version.exists(_ > 0)).label("the write has a version") &&
        assertTrue(reached.keySet == Set(s.first.id, s.second.id)).label(s"the status lists both cells: $reached") &&
        assertTrue(reached.values.forall(_ == version)).label(s"both cells hold version $version: $reached")
    },
    // Phase 2 step 4 (versolauth/versola#1044): the pages of a sign-in behind edge.
    test("through edge a new sign-in is placed and finished at one cell, and a returning browser goes to the cell of its session") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        front <- front
        // A new sign-in, every page of it through edge: placed in the open cell and continued there by the
        // conversation cookie. Its code names the cell it is exchanged at.
        fresh <- s.nextRegistration
        (authorize, submitted) <- submitCode(front, fresh, fresh.email.get)
        placedCode <- submitted.assertRedirect
        placed <- s.first.auth.token(
          placedCode,
          authorize.verifier,
          clientId = Some(fresh.clientId),
          clientSecret = Some(fresh.clientSecret),
          redirectUri = Some(fresh.redirectUri),
        ).success
        placedCell <- cellHeader(placed.accessToken)
        // A user of cell 2, signed in at cell 2 itself: the browser now holds cell 2's session cookie.
        returning <- s.nextRegistration
        (_, atHome) <- submitCode(s.second, returning, returning.email.get)
        session <- ZIO.fromOption(atHome.sessionCookie).orElseFail(RuntimeException("cell 2 set no session cookie"))
        // The same browser comes back through edge: the session cookie's key sends it to cell 2, which knows the
        // session and answers with a code of its own.
        (verifier, challenge) = PkceHelper.generate()
        authorizeUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}/authorize")).mapError(RuntimeException(_))
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
          ).addHeader(Header.Custom("Cookie", s"__Host-SSO_SESSION=$session")),
        )
        location = resumed.headers.get("Location").getOrElse("")
        resumedCode <- ZIO.fromOption(URL.decode(location).toOption.flatMap(_.queryParams.getAll("code").headOption))
          .orElseFail(RuntimeException(s"edge did not answer with a code: ${resumed.status} $location"))
        tokens <- s.second.auth.token(
          resumedCode,
          verifier,
          clientId = Some(returning.clientId),
          clientSecret = Some(returning.clientSecret),
          redirectUri = Some(returning.redirectUri),
        ).success
        resumedCell <- cellHeader(tokens.accessToken)
        notServed <- ZIO.foreach(List("users/claims", "token", "userinfo"))(path =>
          client.batched(Request.get(URL.decode(s"${config.edgeUrl}/$path").toOption.get)).map(_.status),
        )
      yield assertTrue(placedCode.startsWith(s"v1.${s.first.id}."), placedCell.contains(s.first.id))
        .label(s"a new sign-in through edge is placed in the open cell: $placedCode") &&
        assertTrue(session.startsWith(s"v1.${s.second.id}.")).label(s"cell 2's session cookie names it: $session") &&
        assertTrue(resumedCode.startsWith(s"v1.${s.second.id}."), resumedCell.contains(s.second.id))
          .label(s"the returning browser was sent to cell 2: $resumedCode") &&
        assertTrue(notServed.forall(_ == Status.NotFound)).label(s"only the pages of a sign-in are served by edge: $notServed")
    },
    // Phase 2 step 5 (versolauth/versola#1046): a sign-in handed to the cell that holds the address.
    test("a user of cell 2 whose sign-in was placed in cell 1 is handed to cell 2 once the address is typed, and finishes there") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        front <- front
        setup <- s.nextRegistration
        email = setup.email.get
        // The user exists in cell 2.
        registered <- register(s.second, setup, email)
        sub <- subject(registered.tokens.accessToken)
        // A new browser, through edge: nothing says who it is, so the sign-in is placed in the open cell, cell 1.
        authorize <- front.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri)).assertChallengeRedirect
        firstCookie = authorize.conversationCookie.get
        credential <- front.auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        // The address typed is not cell 1's: it asks the directory and sends the browser on with the sign-in sealed.
        typed <- front.auth.submitEmail(firstCookie, email, credential.csrf)
        handoff = typed.location
        handoffUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}$handoff")).mapError(RuntimeException(_))
        // The browser follows it, still holding cell 1's cookie: edge goes by the cell the handoff names.
        taken <- client.batched(Request.get(handoffUrl).addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$firstCookie")))
        secondCookie <- ZIO.fromOption(
          taken.headers.getAll(Header.SetCookie).collectFirst {
            case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
          },
        ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${taken.status}"))
        // Cell 2 knows the user: the address is not asked again, the browser is at the code step.
        otp <- front.auth.getChallenge(secondCookie).assertStep(ConversationStep.Otp)
        code <- front.auth.submitOtp(secondCookie, fixedOtp, otp.csrf).flatMap(_.assertRedirect)
        tokens <- s.second.auth.token(
          code,
          authorize.verifier,
          clientId = Some(setup.clientId),
          clientSecret = Some(setup.clientSecret),
          redirectUri = Some(setup.redirectUri),
        ).success
        subAgain <- subject(tokens.accessToken)
      yield assertTrue(firstCookie.startsWith(s"v1.${s.first.id}.")).label(s"the sign-in was placed in cell 1: $firstCookie") &&
        assertTrue(typed.response.status == Status.SeeOther, handoff.contains(s"/challenge/handoff?handoff=v1.${s.second.id}."))
          .label(s"cell 1 hands the sign-in to cell 2: ${typed.response.status} $handoff") &&
        assertTrue(!handoff.contains(email.takeWhile(_ != '@'))).label("the address is not readable in the handoff") &&
        assertTrue(secondCookie.startsWith(s"v1.${s.second.id}."), code.startsWith(s"v1.${s.second.id}."))
          .label(s"the sign-in continues and finishes in cell 2: $secondCookie $code") &&
        assertTrue(subAgain == sub).label("as the user cell 2 holds, with the PKCE of the request that was placed in cell 1")
    },
    test("a handed-over sign-in lands on the first factor of the client's flow: a password, where the flow asks for one first") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        auth <- ZIO.service[OAuthClient]
        front <- front
        setup <- s.nextRegistration
        email = setup.email.get
        password = "Cell-Stand-Pass-1!"
        // A user of cell 2, with a password an administrator set: central sets it in the cell that holds the user.
        registered <- register(s.second, setup, email)
        sub <- subject(registered.tokens.accessToken)
        userId <- ZIO.fromOption(sub).mapBoth(_ => RuntimeException("the token has no sub"), java.util.UUID.fromString)
        _ <- auth.setUserPassword(userId, password)
        // Through edge with the client whose flow is an email and then a password: placed in cell 1.
        pw = s.passwordClient
        authorize <- front.auth.authorize(clientId = Some(pw.clientId), redirectUri = Some(pw.redirectUri)).assertChallengeRedirect
        firstCookie = authorize.conversationCookie.get
        credential <- front.auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        typed <- front.auth.submitEmail(firstCookie, email, credential.csrf)
        handoffUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${typed.location}")).mapError(RuntimeException(_))
        taken <- client.batched(Request.get(handoffUrl).addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$firstCookie")))
        secondCookie <- ZIO.fromOption(
          taken.headers.getAll(Header.SetCookie).collectFirst {
            case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
          },
        ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${taken.status} ${typed.location}"))
        // Cell 2 asks for what its flow asks first: the password, with no code in between and the address not asked again.
        step <- front.auth.getChallenge(secondCookie).assertStep(ConversationStep.Password)
        code <- front.auth.submitPassword(secondCookie, password, step.csrf).flatMap(_.assertRedirect)
        tokens <- s.second.auth.token(
          code,
          authorize.verifier,
          clientId = Some(pw.clientId),
          clientSecret = Some(pw.clientSecret),
          redirectUri = Some(pw.redirectUri),
        ).success
        subAgain <- subject(tokens.accessToken)
      yield assertTrue(firstCookie.startsWith(s"v1.${s.first.id}."), typed.location.contains(s"handoff=v1.${s.second.id}."))
        .label(s"placed in cell 1 and handed to cell 2: ${typed.location}") &&
        assertTrue(secondCookie.startsWith(s"v1.${s.second.id}."), code.startsWith(s"v1.${s.second.id}."))
          .label("the password was asked and checked in cell 2") &&
        assertTrue(subAgain == sub).label("and the sign-in is the user cell 2 holds")
    },
    test("a request with a login_hint of a user of cell 2, placed in cell 1, is answered with the redirect to cell 2 and goes on there") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        front <- front
        setup <- s.nextRegistration
        email = setup.email.get
        registered <- register(s.second, setup, email)
        sub <- subject(registered.tokens.accessToken)
        // No address is typed: the client names it. The request is placed in cell 1, which does not hold it.
        hinted <- front.auth.authorizeRaw(clientId = setup.clientId, redirectUri = setup.redirectUri, loginHint = Some(email))
        handoffUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${hinted.location}")).mapError(RuntimeException(_))
        taken <- client.batched(Request.get(handoffUrl))
        secondCookie <- ZIO.fromOption(
          taken.headers.getAll(Header.SetCookie).collectFirst {
            case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
          },
        ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${taken.status} ${hinted.location}"))
        otp <- front.auth.getChallenge(secondCookie).assertStep(ConversationStep.Otp)
        code <- front.auth.submitOtp(secondCookie, fixedOtp, otp.csrf).flatMap(_.assertRedirect)
        tokens <- s.second.auth.token(
          code,
          hinted.verifier,
          clientId = Some(setup.clientId),
          clientSecret = Some(setup.clientSecret),
          redirectUri = Some(setup.redirectUri),
        ).success
        subAgain <- subject(tokens.accessToken)
      yield assertTrue(hinted.response.status == Status.SeeOther, hinted.location.contains(s"/challenge/handoff?handoff=v1.${s.second.id}."))
        .label(s"cell 1 answers the request itself with the redirect to cell 2: ${hinted.response.status} ${hinted.location}") &&
        assertTrue(hinted.conversationCookie.isEmpty).label("and sets no cookie of a conversation that is over") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}."), subAgain == sub).label("the sign-in goes on and finishes in cell 2")
    },
    // versolauth/versola#1052: an address nobody holds belongs to a cell too, chosen by central, so that being sent on
    // does not say that an address is registered.
    test("an address nobody holds is sent to the cell central names for it from either cell, and registers there; held or not looks the same") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        front <- front
        setup <- s.nextRegistration
        emails <- ZIO.foreach(List.range(0, 12))(_ => CentralApi.email("placed"))
        // Typed at one cell: on with the sign-in there (the address is that cell's), or sent to the other.
        typedAt = (cell: CellStand.Cell, email: String) =>
          for
            authorize <- cell.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri)).assertChallengeRedirect
            cookie = authorize.conversationCookie.get
            card <- cell.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
            typed <- cell.auth.submitEmail(cookie, email, card.csrf)
          yield (authorize, typed)
        cellFor = (asked: CellStand.Cell, other: CellStand.Cell, typed: SubmitResult) =>
          if typed.location.contains(s"/challenge/handoff?handoff=v1.${other.id}.") then other.id else asked.id
        result <- s.whileSecondTakesUsers:
          for
            // Every address, asked at both cells, before anybody holds it.
            placed <- ZIO.foreach(emails): email =>
              for
                (_, atFirst) <- typedAt(s.first, email)
                (_, atSecond) <- typedAt(s.second, email)
              yield (
                email,
                cellFor(s.first, s.second, atFirst),
                cellFor(s.second, s.first, atSecond),
                atFirst.response.status,
                atSecond.response.status,
              )
            // One that central gave to cell 2: typed in cell 1, it is sent on and the registration is made in cell 2.
            (email, _, _, _, _) <- ZIO.fromOption(placed.find(_._2 == s.second.id))
              .orElseFail(RuntimeException(s"none of ${emails.size} addresses was placed in cell 2: $placed"))
            (authorize, typed) <- typedAt(s.first, email)
            handoffUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${typed.location}")).mapError(RuntimeException(_))
            taken <- client.batched(Request.get(handoffUrl))
            secondCookie <- ZIO.fromOption(
              taken.headers.getAll(Header.SetCookie).collectFirst {
                case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
              },
            ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${taken.status} ${typed.location}"))
            otp <- front.auth.getChallenge(secondCookie).assertStep(ConversationStep.Otp)
            code <- front.auth.submitOtp(secondCookie, fixedOtp, otp.csrf).flatMap(_.assertRedirect)
            tokens <- s.second.auth.token(
              code,
              authorize.verifier,
              clientId = Some(setup.clientId),
              clientSecret = Some(setup.clientSecret),
              redirectUri = Some(setup.redirectUri),
            ).success
            registeredIn <- cellHeader(tokens.accessToken)
            // The same address again, now that cell 2 holds it: cell 1 answers exactly as it did when nobody held it.
            (_, again) <- typedAt(s.first, email)
          yield (placed, typed, code, registeredIn, again)
        (placed, typed, code, registeredIn, again) = result
        shape = (answer: SubmitResult) =>
          (
            answer.response.status,
            answer.location.takeWhile(_ != '=') + "=" + answer.location.dropWhile(_ != '=').drop(1).split('.').take(2).mkString("."),
          )
      yield assertTrue(placed.forall((_, first, second, _, _) => first == second))
        .label(s"both cells name the same cell for an address: ${placed.map(p => (p._2, p._3))}") &&
        assertTrue(placed.map(_._2).toSet == Set(s.first.id, s.second.id)).label("and over a dozen addresses both cells are named") &&
        assertTrue(placed.forall((_, _, _, first, second) => first == Status.SeeOther && second == Status.SeeOther))
          .label("each answer is the same 303, whichever it is") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}."), registeredIn.contains(s.second.id))
          .label(s"the registration was made in the cell central named: $code") &&
        assertTrue(shape(again) == shape(typed))
          .label(s"held or not, cell 1 answers alike: ${shape(typed)} then ${shape(again)}")
    },
    // versolauth/versola#1052: a cell that does not take users is sent a held address and an unheld one alike, and a registration
    // that is verified there is made in a cell that does.
    test("a closed cell is sent a held address and an unheld one alike, and an unheld one registers in the open cell") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        front <- front
        heldSetup <- s.nextRegistration
        setup <- s.nextRegistration
        heldEmail = heldSetup.email.get
        // A user of cell 2, made while it takes users.
        _ <- register(s.second, heldSetup, heldEmail)
        result <- s.whileSecondClosedAtFullWeight:
          for
            // (a) the held address, typed in the open cell: sent to cell 2, which holds the user, and shown the code step.
            (_, _, heldTyped) <- typedAt(s.first, heldSetup, heldEmail)
            heldArrived <- visited(heldTyped.location, None)
            heldCookie <- ZIO.fromOption(
              conversationCookieOf(heldArrived),
            ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${heldArrived.status}"))
            heldStep <- front.auth.getChallenge(heldCookie).assertStep(ConversationStep.Otp)
            // (b) addresses nobody holds, until one is sent to the closed cell: bounded, and a failure says how many were tried.
            found <- ZIO.iterate((0, Option.empty[(String, AuthorizeResult, SubmitResult)]))(state => state._2.isEmpty && state._1 < 40):
              case (tries, _) =>
                for
                  email <- CentralApi.email("closed")
                  (authorize, _, typed) <- typedAt(s.first, setup, email)
                yield (tries + 1, Option.when(handedTo(s.second, typed))((email, authorize, typed)))
            (tries, chosen) = found
            (email, authorize, typed) <- ZIO.fromOption(chosen)
              .orElseFail(RuntimeException(s"none of $tries unheld addresses was sent to the closed cell"))
            arrived <- visited(typed.location, None)
            cookie <-
              ZIO.fromOption(conversationCookieOf(arrived)).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${arrived.status}"))
            step <- front.auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
            // The code is typed in the closed cell, which refuses to register and hands the verified registration to the open one.
            typedCode <- front.auth.submitOtp(cookie, fixedOtp, step.csrf)
            registration <- visited(typedCode.location, Some(cookie))
            code <- SubmitResult(registration).assertRedirect
            tokens <- s.first.auth.token(
              code,
              authorize.verifier,
              clientId = Some(setup.clientId),
              clientSecret = Some(setup.clientSecret),
              redirectUri = Some(setup.redirectUri),
            ).success
            issuedBy <- cellHeader(tokens.accessToken)
            sub <- subject(tokens.accessToken)
            inDirectory <- central.get("/users", "email" -> email).flatMap(_.items("users"))
            // The same address again: the closed cell sends it straight to the open cell, and the open cell asks for its code itself.
            (_, _, atClosed) <- typedAt(s.second, setup, email)
            (_, againCookie, atOpen) <- typedAt(s.first, setup, email)
            againStep <- s.first.auth.getChallenge(againCookie).assertStep(ConversationStep.Otp)
          yield (tries, heldTyped, heldStep, typed, step, typedCode, code, issuedBy, sub, inDirectory, atClosed, atOpen, againStep)
        (tries, heldTyped, heldStep, typed, step, typedCode, code, issuedBy, sub, inDirectory, atClosed, atOpen, againStep) = result
        shape = (answer: SubmitResult) =>
          (
            answer.response.status,
            answer.location.takeWhile(_ != '=') + "=" + answer.location.dropWhile(_ != '=').drop(1).split('.').take(2).mkString("."),
          )
      yield assertTrue(handedTo(s.second, heldTyped), handedTo(s.second, typed))
        .label(s"a held address and an unheld one are both sent to the closed cell (after $tries tries): ${heldTyped.location} ${typed.location}") &&
        assertTrue(shape(heldTyped) == shape(typed))
          .label(s"and the answer to either has the same shape: ${shape(heldTyped)} and ${shape(typed)}") &&
        assertTrue(heldStep.getClass == step.getClass).label("both are shown the code step") &&
        assertTrue(typedCode.location.contains(s"/challenge/registration?registration=v1.${s.first.id}."))
          .label(s"the code verified in the closed cell is handed to the open one: ${typedCode.response.status} ${typedCode.location}") &&
        assertTrue(code.startsWith(s"v1.${s.first.id}."), issuedBy.contains(s.first.id))
          .label(s"the tokens are issued in the open cell: $code $issuedBy") &&
        assertTrue(inDirectory.flatMap(_.str("cell")) == Chunk(s.first.id), inDirectory.flatMap(_.str("id")) == Chunk.fromIterable(sub))
          .label(s"the directory says the user is the open cell's: $inDirectory") &&
        assertTrue(handedTo(
          s.first,
          atClosed,
        )).label(s"a second sign-in with the address goes from the closed cell straight to the open one: ${atClosed.location}") &&
        assertTrue(!atOpen.location.contains("/challenge/handoff"), againStep.csrf.nonEmpty)
          .label(s"and in the open cell it stays and asks for the code: ${atOpen.location}")
    },
    // versolauth/versola#1048: what names the user only when it is submitted is repeated in the cell that holds the user.
    test("a login and password typed where the sign-in was placed are checked in the cell that holds the user, once, and not asked twice") {
      for
        s <- stand
        front <- front
        user <- loginUserOfSecond(s)
        // A new browser through edge: placed in cell 1, which does not hold the login.
        (authorize, firstCookie, card) <- placed(front, s.loginClient)
        form = Map("login" -> user.login, "password" -> user.password, "csrf" -> card.csrf)
        typed <- front.auth.submitLoginPassword(firstCookie, user.login, user.password, card.csrf)
        // The browser repeats the POST where it was sent, still holding cell 1's cookie: cell 2 checks it and finishes.
        finished <- repeated(typed, form, Some(firstCookie))
        code <- finished.assertRedirect
        tokens <- s.second.auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.loginClient.clientId),
          clientSecret = Some(s.loginClient.clientSecret),
          redirectUri = Some(s.loginClient.redirectUri),
        ).success
        subAgain <- subject(tokens.accessToken)
        // The same request a second time: the seal was taken.
        again <- repeated(typed, form, Some(firstCookie))
      yield assertTrue(firstCookie.startsWith(s"v1.${s.first.id}.")).label(s"the sign-in was placed in cell 1: $firstCookie") &&
        assertTrue(sentTo(s.second, typed)).label(s"cell 1 has the browser repeat the POST at cell 2: ${typed.response.status} ${typed.location}") &&
        assertTrue(!typed.location.contains(user.login), !typed.location.contains(user.password))
          .label("neither the login nor the password is in the address") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}."), subAgain == user.sub)
          .label(s"cell 2 signed its user in, with the PKCE of the request placed in cell 1: $code") &&
        assertTrue(again.response.status == Status.BadRequest).label(s"a seal is taken once: ${again.response.status}")
    },
    test("a submission handed over is refused without the cookie of the browser it was made in, and that does not spend it") {
      for
        s <- stand
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        front <- front
        user <- loginUserOfSecond(s)
        (_, firstCookie, card) <- placed(front, s.loginClient)
        form = Map("login" -> user.login, "password" -> user.password, "csrf" -> card.csrf)
        typed <- front.auth.submitLoginPassword(firstCookie, user.login, user.password, card.csrf)
        // Posted from somewhere that holds no conversation, and from a browser that holds another one.
        bare <- repeated(typed, form, None)
        (_, otherCookie, _) <- placed(front, s.loginClient)
        other <- repeated(typed, form, Some(otherCookie))
        // Followed as a link: there is no submission to check.
        url <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${typed.location}")).mapError(RuntimeException(_))
        followed <- client.batched(Request.get(url).addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$firstCookie")))
        // The browser it was made in is still served.
        finished <- repeated(typed, form, Some(firstCookie))
        code <- finished.assertRedirect
      yield assertTrue(sentTo(s.second, typed)).label(s"handed to cell 2: ${typed.response.status} ${typed.location}") &&
        assertTrue(bare.response.status == Status.BadRequest).label(s"no cookie: ${bare.response.status}") &&
        assertTrue(other.response.status == Status.BadRequest).label(s"another conversation's cookie: ${other.response.status}") &&
        assertTrue(followed.status == Status.BadRequest).label(s"a GET of it: ${followed.status}") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}.")).label("and the refusals did not spend the seal")
    },
    test("a wrong password for a login of cell 2 and a login nobody holds end the same way: the credential card, no sign-in") {
      for
        s <- stand
        front <- front
        user <- loginUserOfSecond(s)
        // The right login of cell 2 with a wrong password: handed over, and refused there.
        (_, firstCookie, card) <- placed(front, s.loginClient)
        wrong = "Wrong-Pass-9!"
        typed <- front.auth.submitLoginPassword(firstCookie, user.login, wrong, card.csrf)
        refused <- repeated(typed, Map("login" -> user.login, "password" -> wrong, "csrf" -> card.csrf), Some(firstCookie))
        // A login nobody holds: central names a cell for it all the same, this one or the other.
        nobody <- CentralApi.login("nobody")
        (_, unknownCookie, unknownCard) <- placed(front, s.loginClient)
        unknown <- front.auth.submitLoginPassword(unknownCookie, nobody, wrong, unknownCard.csrf)
        ended <-
          if unknown.response.status == Status.TemporaryRedirect then
            repeated(unknown, Map("login" -> nobody, "password" -> wrong, "csrf" -> unknownCard.csrf), Some(unknownCookie))
          else ZIO.succeed(unknown)
      yield assertTrue(sentTo(s.second, typed)).label(s"the login of cell 2 is handed over: ${typed.response.status}") &&
        assertTrue(refused.response.status == Status.SeeOther, refused.location.startsWith("/challenge"))
          .label(s"cell 2 refuses the wrong password on its own credential card: ${refused.response.status} ${refused.location}") &&
        assertTrue(unknown.response.status == Status.SeeOther || sentTo(s.second, unknown))
          .label(s"a login nobody holds is answered here or sent on, as a held one is: ${unknown.response.status}") &&
        assertTrue(ended.response.status == Status.SeeOther, ended.location.startsWith("/challenge"))
          .label(s"and ends on a credential card like the wrong password did: ${ended.response.status} ${ended.location}")
    },
    test("a passkey used with no address typed where the sign-in was placed is verified in the cell that holds the user, with one touch") {
      for
        s <- stand
        front <- front
        user <- loginUserOfSecond(s)
        passkey <- enrollPasskeyAtSecond(s, user)
        // Through edge: cell 1 issues the assertion request, the authenticator answers it for the user of cell 2.
        (authorize, firstCookie, card) <- placed(front, s.loginClient)
        options <- front.auth.getPasskeyOptions(firstCookie).flatMap(_.body.asString)
        assertion <- TestAuthenticator.get(passkey, options, Flows.passkeyOrigin, user.userId)
        form = Map("response" -> assertion.toJson, "csrf" -> card.csrf)
        submitted <- front.auth.submitPasskeyAssertion(firstCookie, assertion.toJson, card.csrf)
        // Cell 2 verifies the same assertion against the request cell 1 issued, which came sealed.
        finished <- repeated(submitted, form, Some(firstCookie))
        code <- finished.assertRedirect
        tokens <- s.second.auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.loginClient.clientId),
          clientSecret = Some(s.loginClient.clientSecret),
          redirectUri = Some(s.loginClient.redirectUri),
        ).success
        subAgain <- subject(tokens.accessToken)
        // The assertion a second time, as somebody who captured the request would send it.
        again <- repeated(submitted, form, Some(firstCookie))
      yield assertTrue(sentTo(
        s.second,
        submitted,
      )).label(s"cell 1 hands the assertion to cell 2: ${submitted.response.status} ${submitted.location}") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}."), subAgain == user.sub).label(s"cell 2 signed its user in: $code") &&
        assertTrue(again.response.status == Status.BadRequest).label(s"the assertion is not good twice: ${again.response.status}")
    },
    test("a sign-in handed to cell 2 ends the session the browser had in cell 1 once it has finished there, and not before") {
      for
        s <- stand
        front <- front
        // A user of cell 1, signed in there: the browser holds cell 1's session cookie, and a client holds a token of it.
        first <- s.nextRegistration
        (authorized, signedIn) <- submitCode(s.first, first, first.email.get)
        session <- ZIO.fromOption(signedIn.sessionCookie).orElseFail(RuntimeException("cell 1 set no session cookie"))
        firstCode <- signedIn.assertRedirect
        held <- s.first.auth.token(
          firstCode,
          authorized.verifier,
          clientId = Some(first.clientId),
          clientSecret = Some(first.clientSecret),
          redirectUri = Some(first.redirectUri),
        ).success
        other <- loginUserOfSecond(s)
        // The same browser is asked to sign in again (`prompt=login`): its session cookie sends the request to cell 1,
        // whose card takes another user, and the user typed is cell 2's.
        again <- front.auth.authorizeRaw(
          clientId = s.loginClient.clientId,
          redirectUri = s.loginClient.redirectUri,
          prompt = Some("login"),
          sessionCookie = Some(session),
        ).assertChallengeRedirect
        firstCookie = again.conversationCookie.get
        card <- front.auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        wrong = "Wrong-Pass-9!"
        typed <- front.auth.submitLoginPassword(firstCookie, other.login, wrong, card.csrf)
        // A wrong password: handed over and refused in cell 2. Nothing has finished, so the session in cell 1 stays.
        refused <- repeated(typed, Map("login" -> other.login, "password" -> wrong, "csrf" -> card.csrf), Some(firstCookie))
        secondCookie <- ZIO.fromOption(
          refused.response.headers.getAll(Header.SetCookie).collectFirst {
            case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
          },
        ).orElseFail(RuntimeException(s"cell 2 did not take the handoff: ${refused.response.status}"))
        untouched <- s.first.auth.userinfo(held.accessToken).map(_.response.status)
        // The right password, on cell 2's own card now: the sign-in finishes there.
        retry <- front.auth.getChallenge(secondCookie).assertStep(ConversationStep.Credential)
        code <- front.auth.submitLoginPassword(secondCookie, other.login, other.password, retry.csrf).flatMap(_.assertRedirect)
        // Awaited: cell 2 tells central, central tells cell 1, which ends the session and with it the tokens it issued.
        ended <- s.first.auth.userinfo(held.accessToken).map(_.response.status)
          .repeat(Schedule.spaced(200.millis) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
          .map(_._2)
          .timeoutFail(RuntimeException("cell 1 still honours a token of the session that was replaced"))(15.seconds)
        // And the cookie of that session signs nobody in any more.
        resumed <- s.first.auth.authorizeRaw(clientId = first.clientId, redirectUri = first.redirectUri, sessionCookie = Some(session))
      yield assertTrue(firstCookie.startsWith(s"v1.${s.first.id}.")).label(s"the session cookie sent the request to cell 1: $firstCookie") &&
        assertTrue(sentTo(s.second, typed)).label(s"the user typed is cell 2's: ${typed.response.status} ${typed.location}") &&
        assertTrue(secondCookie.startsWith(s"v1.${s.second.id}."), untouched == Status.Ok)
          .label(s"a sign-in that did not finish leaves the session in cell 1 alone: $untouched") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}."), ended == Status.Unauthorized)
          .label(s"once it has finished in cell 2, the session in cell 1 is ended: $ended") &&
        assertTrue(resumed.location.contains("/challenge")).label(s"and its cookie asks for a sign-in again: ${resumed.location}")
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
    // versolauth/versola#1051: a user changes its cell. Central drives the move; the cells hand the rows to each other sealed.
    test(
      "a user moved to another cell signs in there with the same password and passkey; its refresh token is answered moved by the old cell and replaced by the new one",
    ) {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        front <- front
        user <- loginUserOfSecond(s)
        passkey <- enrollPasskeyAtSecond(s, user)
        before <- signInAt(s.second, s.loginClient, user, scope = "openid offline_access")
        carried <- ZIO.fromOption(before.refreshToken).orElseFail(RuntimeException("cell 2 issued no refresh token"))
        record <- moved(central, user.userId.toString, s.first.id)
        // The old cell does not have the token any more, and says where it went.
        atOld <- s.second.auth.refresh(carried, clientId = Some(s.loginClient.clientId), clientSecret = Some(s.loginClient.clientSecret))
        oldAnswer = atOld match
          case TokenResult.Failure(response, body) => (response.status.code, body)
          case _: TokenResult.Success => (200, "")
        // The new cell takes the token cell 2 issued, once, and answers with one of its own.
        atNew <- s.first.auth.refresh(carried, clientId = Some(s.loginClient.clientId), clientSecret = Some(s.loginClient.clientSecret)).success
        own <- ZIO.fromOption(atNew.refreshToken).orElseFail(RuntimeException("cell 1 answered a carried token with no refresh token"))
        newCell <- cellHeader(atNew.accessToken)
        newSub <- subject(atNew.accessToken)
        again <- s.first.auth.refresh(own, clientId = Some(s.loginClient.clientId), clientSecret = Some(s.loginClient.clientSecret)).success
        // Through edge, as a browser: the sign-in is placed in cell 1, which now holds the login and its password.
        (authorize, cookie, card) <- placed(front, s.loginClient)
        code <- front.auth.submitLoginPassword(cookie, user.login, user.password, card.csrf).flatMap(_.assertRedirect)
        byPassword <- s.first.auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.loginClient.clientId),
          clientSecret = Some(s.loginClient.clientSecret),
          redirectUri = Some(s.loginClient.redirectUri),
        ).success
        passwordSub <- subject(byPassword.accessToken)
        // The passkey enrolled in cell 2, used in cell 1 with no address typed.
        (_, passkeyCookie, passkeyCard) <- placed(front, s.loginClient)
        options <- front.auth.getPasskeyOptions(passkeyCookie).flatMap(_.body.asString)
        assertion <- TestAuthenticator.get(passkey, options, Flows.passkeyOrigin, user.userId)
        byPasskey <- front.auth.submitPasskeyAssertion(passkeyCookie, assertion.toJson, passkeyCard.csrf)
        passkeyCode <- byPasskey.assertRedirect
        // Typed at cell 2 itself, the login is another cell's now: the browser is sent to cell 1.
        atSecond <- s.second.auth.authorize(clientId = Some(s.loginClient.clientId), redirectUri = Some(s.loginClient.redirectUri))
          .assertChallengeRedirect
        secondCard <- s.second.auth.getChallenge(atSecond.conversationCookie.get).assertStep(ConversationStep.Credential)
        typedAtSecond <- s.second.auth.submitLoginPassword(atSecond.conversationCookie.get, user.login, user.password, secondCard.csrf)
        old <- cellOfTopology(central, s.second.id)
      yield assertTrue(record.str("cell").contains(s.first.id)).label(s"the directory names cell 1: ${record.toJson}") &&
        assertTrue(oldAnswer._1 == 421, oldAnswer._2.contains("\"moved\""), oldAnswer._2.contains(s.first.id))
          .label(s"cell 2 answers the carried token with moved and the cell: $oldAnswer") &&
        assertTrue(own != carried, own.startsWith(s"v1.${s.first.id}."), newCell.contains(s.first.id), newSub == user.sub)
          .label(s"cell 1 replaced the carried token with one of its own: $own") &&
        assertTrue(again.refreshToken.nonEmpty).label("the replacement is refreshed at cell 1 like any token of it") &&
        assertTrue(code.startsWith(s"v1.${s.first.id}."), passwordSub == user.sub).label(s"the password signs the user in at cell 1: $code") &&
        assertTrue(passkeyCode.startsWith(s"v1.${s.first.id}.")).label(s"the passkey signs the user in at cell 1: $passkeyCode") &&
        assertTrue(sentTo(s.first, typedAtSecond))
          .label(s"cell 2 no longer holds the user and hands it on: ${typedAtSecond.response.status} ${typedAtSecond.location}") &&
        assertTrue(number(old, "tokens").exists(_ >= 1)).label(s"cell 2 reports the tokens it still answers moved for: ${old.toJson}")
    },
    test("an edge session of a user moved to another cell goes on: userinfo is asked again at the cell the old one names") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        auth <- ZIO.service[OAuthClient]
        edge <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        // A user the directory places in cell 1 (the fixture's own user was made before there were cells, and is in none).
        user <- loginUserOf(s, s.first)
        // The role that permits the fixture's endpoints: it travels with the user.
        _ <- auth.assignUserRoles(user.userId, Set(f.roleId))
        _ <- auth.flushUserOutbox()
        session <- edge.browserLogin(auth, f.presetId, user.login, user.password)
        before <- edge.proxy(Method.GET, f.resourceId, "/profile", session.auth)
        record <- moved(central, user.userId.toString, s.second.id)
        // The session's token still names cell 1, which answers moved; the edge asks cell 2, which holds the user now.
        profile <- edge.proxy(Method.GET, f.resourceId, "/profile", session.auth)
      yield assertTrue(before.status == Status.Ok).label(s"the session works before the move: ${before.status}") &&
        assertTrue(record.str("cell").contains(s.second.id)).label(s"the directory names cell 2: ${record.toJson}") &&
        assertTrue(profile.status == Status.Ok).label(s"the edge got userinfo from the user's new cell: ${profile.status} ${profile.body}")
    },
    test("a draining cell is emptied into the open cell of its partition, and a user of it signs in there") {
      for
        s <- stand
        central <- ZIO.service[CentralApi]
        user <- loginUserOfSecond(s)
        // Central moves a few users at a time; awaited until the directory places nobody in cell 2 and no move is under way.
        emptied <- s.whileSecondDrains:
          cellOfTopology(central, s.second.id)
            .repeat(
              Schedule.recurUntil[Json.Obj](cell => number(cell, "users").contains(0L) && number(cell, "moving").contains(0L)) &&
                Schedule.spaced(1.second) && Schedule.recurs(240),
            ).map(_._1)
        _ <- ZIO.fail(RuntimeException(s"cell 2 was not emptied within four minutes: ${emptied.toJson}"))
          .unless(number(emptied, "users").contains(0L) && number(emptied, "moving").contains(0L))
        record <- central.get("/users", "id" -> user.userId.toString).flatMap(_.items("users")).map(_.headOption)
        tokens <- signInAt(s.first, s.loginClient, user)
        sub <- subject(tokens.accessToken)
      yield assertTrue(number(emptied, "users").contains(0L), number(emptied, "moving").contains(0L))
        .label(s"cell 2 was emptied: ${emptied.toJson}") &&
        assertTrue(record.flatMap(_.str("cell")).contains(s.first.id)).label(s"the directory names cell 1 for its user: ${record.map(_.toJson)}") &&
        assertTrue(sub == user.sub).label("the user signs in at cell 1 with the password it had in cell 2")
    },
  )
