package versola.e2e.flows.revocation

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}

/** What an administrator's change to a user does to the access tokens the user already holds: a password
  * set or reset, the revoking of a client's offline access, the removal of a role. `auth` refuses those
  * tokens at `/userinfo` by itself (the client registered no back channel), and each case checks what is
  * meant to survive as well as what is meant to stop: the refresh token, which is how the user gets a
  * token that reflects the change, and other users' tokens.
  *
  * Every test signs a user of its own in: these tests end that user's access.
  *
  * `iat` is whole seconds, and a change to the user refuses only the tokens issued in an earlier second
  * than the change's own: a token minted in the second of the change is the user's own, from signing in
  * or refreshing right after it, and is accepted (the trade-off is a token minted in that second just
  * before the change, which works until it expires). So a test that expects a token it held to be refused
  * lets the clock pass into the next second before it makes the change ([[nextSecond]]), and a test that
  * expects a token to work makes no wait at all: signing in or refreshing straight after the change is
  * the case being tested.
  */
object UserChangeRevocationFlowSpec extends E2ESpec:

  /** How long a revocation may take to reach the replica that answers: a notification, normally
    * milliseconds. Generous, because what is awaited is the refusal, not the speed. */
  private val Propagation = 15.seconds

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"
  private val offlineScope = "openid email offline_access"

  private case class Login(tokens: TokenResult.Success, sessionCookie: String)

  private case class OAuthError(error: String) derives JsonDecoder

  private case class Claims(roles: List[String]) derives JsonDecoder

  /** A user registered through central and known to auth, so the email+OTP client signs them in. */
  private def freshUser(auth: OAuthClient): Task[(UUID, String)] =
    for
      email <- CentralApi.email("change-revocation")
      userId <- auth.registerUser(email = Some(email))
      _ <- auth.flushUserOutbox()
    yield (userId, email)

  private def login(s: Flows.Setup, auth: OAuthClient, email: String): Task[Login] =
    for
      authorize <- auth.authorize(scope = offlineScope, clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, email, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      code <- submit.assertRedirect
      sessionCookie <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie after the OTP submission"))
      tokens <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
    yield Login(tokens, sessionCookie)

  private def refreshTokenOf(login: Login): Task[String] =
    ZIO.fromOption(login.tokens.refreshToken).orElseFail(RuntimeException("Expected a refresh token for the offline_access scope"))

  private def userinfoStatus(auth: OAuthClient, accessToken: String): Task[Status] =
    auth.userinfo(accessToken).map(_.response.status)

  /** Polls `/userinfo` until it refuses the token, or fails once `Propagation` has passed. */
  private def awaitRefused(auth: OAuthClient, accessToken: String): Task[Status] =
    userinfoStatus(auth, accessToken)
      .repeat(Schedule.spaced(100.millis) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
      .map(_._2)
      .timeoutFail(RuntimeException(s"/userinfo still honoured the token after $Propagation"))(Propagation)

  /** Returns once the wall clock is in a later second than it was when called, which is when a token issued before
    * the call has an `iat` strictly before the second of whatever is done next. Polled against the clock rather than
    * slept: the auth process stamps the change from the same host's clock, and this takes as long as the second has
    * left, never longer. */
  private val nextSecond: Task[Unit] =
    Clock.instant.flatMap: start =>
      Clock.instant
        .repeat(Schedule.spaced(20.millis) && Schedule.recurUntil[Instant](_.getEpochSecond > start.getEpochSecond))
        .timeoutFail(RuntimeException("the clock did not reach the next second"))(5.seconds)
        .unit

  /** One refresh, and the check that `/userinfo` accepts the access token it yielded: made straight after the change,
    * which is the case that used to be refused when the token was minted in the change's own second. */
  private def refreshAccepted(s: Flows.Setup, auth: OAuthClient, refreshToken: String): Task[(TokenResult.Success, Status)] =
    for
      refreshed <- auth.refresh(refreshToken, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).success
      status <- userinfoStatus(auth, refreshed.accessToken)
    yield (refreshed, status)

  private def refresh(s: Flows.Setup, auth: OAuthClient, refreshToken: String): Task[TokenResult] =
    auth.refresh(refreshToken, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret))

  /** The `error` code of a rejected `/token` call. */
  private def rejection(result: TokenResult): Task[String] =
    result match
      case s: TokenResult.Success => ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(_, body) =>
        ZIO.fromEither(body.fromJson[OAuthError]).mapBoth(err => RuntimeException(s"Unparsable /token error body [$err]: $body"), _.error)

  /** Whether the session cookie still signs the user in without interaction. */
  private def silentlyAuthorizes(s: Flows.Setup, auth: OAuthClient, sessionCookie: String): Task[Boolean] =
    auth.authorizeRaw(
      clientId = s.clientId,
      redirectUri = s.redirectUri,
      scope = Some("openid"),
      prompt = Some("none"),
      sessionCookie = Some(sessionCookie),
    )
      .map(_.location.contains("code="))

  /** The `roles` claim of an access token, which is a JWT. */
  private def rolesOf(accessToken: String): Task[Set[String]] =
    for
      payload <- ZIO.attempt(String(Base64.getUrlDecoder.decode(accessToken.split('.')(1)), StandardCharsets.UTF_8))
      claims <- ZIO.fromEither(payload.fromJson[Claims]).mapError(error => RuntimeException(s"access token has no roles claim [$error]: $payload"))
    yield claims.roles.toSet

  private def withRoles[A](auth: OAuthClient, ids: List[String])(use: => Task[A]): Task[A] =
    ZIO.foreachDiscard(ids)(id => auth.central.post("/configuration/roles", Fixtures.role(id))) *>
      use.ensuring(
        ZIO.foreachDiscard(ids)(id => auth.central.delete("/configuration/roles", "tenantId" -> Fixtures.suiteTenant, "roleId" -> id).ignore),
      )

  /** How many times the change-then-straight-away cases are repeated. Each round lands in the change's own second
    * with a probability of most of one (a sign-in or a refresh takes a few hundred milliseconds), so a refusal of
    * that second shows within a round or two; this many makes the case fail reliably on the unfixed rule rather
    * than about once in a few runs. */
  private val Rounds = 8

  /** A user with a login and no password yet, on the client that signs in with login and password. */
  private def freshLoginUser(auth: OAuthClient): Task[(UUID, String)] =
    for
      login <- CentralApi.login("change-revocation")
      userId <- auth.registerUser(login = Some(login))
      _ <- auth.flushUserOutbox()
    yield (userId, login)

  private def loginWithPassword(s: Flows.Setup, auth: OAuthClient, login: String, password: String): Task[TokenResult.Success] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, login, password, challenge.csrf).assertRedirect(auth, cookie)
      tokens <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
    yield tokens

  def spec = suite("Access tokens after an administrator changes the user")(
    suite("password set")(
      test("refuses the tokens issued before it, and lets the user refresh into one that works") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          (userId, email) <- freshUser(auth)
          (bystanderId, bystanderEmail) <- freshUser(auth)
          held <- login(s, auth, email)
          bystander <- login(s, auth, bystanderEmail)
          refreshToken <- refreshTokenOf(held)
          before <- userinfoStatus(auth, held.tokens.accessToken)
          _ <- nextSecond
          _ <- auth.setUserPassword(userId, "Admin-Set-Pass-1!")
          after <- awaitRefused(auth, held.tokens.accessToken)
          (refreshed, refreshedStatus) <- refreshAccepted(s, auth, refreshToken)
          signedIn <- silentlyAuthorizes(s, auth, held.sessionCookie)
          bystanderAfter <- userinfoStatus(auth, bystander.tokens.accessToken)
        yield assertTrue(before == Status.Ok).label("the token works until the password is set") &&
          assertTrue(after == Status.Unauthorized).label("a token issued before an administrator set the password is refused") &&
          assertTrue(refreshedStatus == Status.Ok).label("the refresh token is not touched: it yields a token that works, straight away") &&
          assertTrue(signedIn).label("the session is not ended by a password set") &&
          assertTrue(bystanderAfter == Status.Ok).label("another user's token is not affected")
      },
    ),
    suite("password reset")(
      test("refuses the tokens issued before it and signs the user out everywhere, while another user is not affected") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          (userId, email) <- freshUser(auth)
          (bystanderId, bystanderEmail) <- freshUser(auth)
          held <- login(s, auth, email)
          bystander <- login(s, auth, bystanderEmail)
          refreshToken <- refreshTokenOf(held)
          before <- userinfoStatus(auth, held.tokens.accessToken)
          _ <- nextSecond
          _ <- auth.resetUserPassword(userId)
          after <- awaitRefused(auth, held.tokens.accessToken)
          // A reset is what an administrator does when the account may be in someone else's hands, so unlike a
          // password set it ends the sessions and the refresh tokens too: nothing the user held before it still works.
          refreshError <- refresh(s, auth, refreshToken).flatMap(rejection)
          signedIn <- silentlyAuthorizes(s, auth, held.sessionCookie)
          bystanderAfter <- userinfoStatus(auth, bystander.tokens.accessToken)
        yield assertTrue(before == Status.Ok) &&
          assertTrue(after == Status.Unauthorized).label("a token issued before an administrator reset the password is refused") &&
          assertTrue(refreshError == "invalid_grant").label("the refresh token is ended by the reset") &&
          assertTrue(!signedIn).label("the session is ended by the reset") &&
          assertTrue(bystanderAfter == Status.Ok).label("another user's token is not affected")
      },
    ),
    suite("straight after the change")(
      test("a user who signs in right after an administrator sets the password is not refused, round after round") {
        for
          (s, auth) <- setup(Flows.Id.LoginPassword)
          (userId, login) <- freshLoginUser(auth)
          // Each round is a set and then a sign-in with no pause: the token's `iat` is in the second of the change.
          statuses <- ZIO.foreach((1 to Rounds).toList): round =>
            val password = s"Admin-Set-Pass-$round-Aa!"
            for
              _ <- auth.setUserPassword(userId, password)
              tokens <- loginWithPassword(s, auth, login, password)
              status <- userinfoStatus(auth, tokens.accessToken)
            yield status
        yield assertTrue(statuses == List.fill(Rounds)(Status.Ok))
          .label(s"/userinfo refused a token issued after the password was set: $statuses")
      },
      test("a refresh right after a role is removed yields a token that works and carries the roles held now, round after round") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          kept <- CentralApi.id("rev-role-kept")
          removable <- CentralApi.id("rev-role-removable")
          result <- withRoles(auth, List(kept, removable)):
            for
              (userId, email) <- freshUser(auth)
              _ <- auth.assignUserRoles(userId, Set(kept))
              _ <- auth.flushUserOutbox()
              held <- login(s, auth, email)
              first <- refreshTokenOf(held)
              // Each round grants the role and takes it away again, then refreshes at once: the removal revokes the
              // tokens issued before it, and the refresh token is how the user gets one that is not among them.
              rounds <- ZIO.foldLeft((1 to Rounds).toList)((first, List.empty[(Status, Set[String])])):
                case ((refreshToken, seen), _) =>
                  for
                    _ <- auth.assignUserRoles(userId, Set(removable))
                    _ <- auth.flushUserOutbox()
                    _ <- auth.removeUserRoles(userId, Set(removable))
                    _ <- auth.flushUserOutbox()
                    (refreshed, status) <- refreshAccepted(s, auth, refreshToken)
                    roles <- rolesOf(refreshed.accessToken)
                    rotated <- refreshTokenOf(Login(refreshed, held.sessionCookie))
                  yield (rotated, seen :+ (status -> roles))
            yield assertTrue(rounds._2 == List.fill(Rounds)(Status.Ok -> Set(kept)))
              .label(s"each refreshed token must pass /userinfo and carry only the role still held: ${rounds._2}")
        yield result
      },
    ),
    suite("offline access")(
      test("revoking a client's offline access refuses the access tokens its grant issued, and the refresh token, but not the session") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          (userId, email) <- freshUser(auth)
          (_, otherEmail) <- freshUser(auth)
          held <- login(s, auth, email)
          other <- login(s, auth, otherEmail)
          refreshToken <- refreshTokenOf(held)
          before <- userinfoStatus(auth, held.tokens.accessToken)
          revoked <- auth.central.delete("/users/refresh-tokens", "id" -> userId.toString, "clientId" -> s.clientId)
          after <- awaitRefused(auth, held.tokens.accessToken)
          error <- refresh(s, auth, refreshToken).flatMap(rejection)
          signedIn <- silentlyAuthorizes(s, auth, held.sessionCookie)
          otherAfter <- userinfoStatus(auth, other.tokens.accessToken)
        yield assertTrue(before == Status.Ok) &&
          assertTrue(revoked.status == Status.NoContent) &&
          assertTrue(after == Status.Unauthorized).label("the access token the revoked grant issued must be refused") &&
          assertTrue(error == "invalid_grant").label("the revoked refresh token must not redeem") &&
          assertTrue(signedIn).label("revoking offline access is not a logout: the session stays") &&
          assertTrue(otherAfter == Status.Ok).label("another user's grant to the same client is not affected")
      },
    ),
    suite("roles")(
      test("removing a role refuses the earlier tokens and refresh yields one with the roles held now; adding one revokes nothing") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          roleA <- CentralApi.id("rev-role-a")
          roleB <- CentralApi.id("rev-role-b")
          roleC <- CentralApi.id("rev-role-c")
          result <- withRoles(auth, List(roleA, roleB, roleC)):
            for
              (userId, email) <- freshUser(auth)
              _ <- auth.assignUserRoles(userId, Set(roleA, roleB))
              _ <- auth.flushUserOutbox()
              held <- login(s, auth, email)
              refreshToken <- refreshTokenOf(held)
              heldRoles <- rolesOf(held.tokens.accessToken)
              // Adding a role says nothing about the tokens already issued: they still work.
              _ <- auth.assignUserRoles(userId, Set(roleC))
              _ <- auth.flushUserOutbox()
              afterAdd <- userinfoStatus(auth, held.tokens.accessToken)
              _ <- nextSecond
              _ <- auth.removeUserRoles(userId, Set(roleA))
              _ <- auth.flushUserOutbox()
              afterRemove <- awaitRefused(auth, held.tokens.accessToken)
              // `/token` does not ask the list: the refresh token is how the user gets a token without the role.
              (refreshed, refreshedStatus) <- refreshAccepted(s, auth, refreshToken)
              refreshedRoles <- rolesOf(refreshed.accessToken)
            yield assertTrue(heldRoles == Set(roleA, roleB)) &&
              assertTrue(afterAdd == Status.Ok).label("adding a role must not refuse the tokens issued before it") &&
              assertTrue(afterRemove == Status.Unauthorized).label("a token issued before a role was removed must be refused") &&
              assertTrue(refreshedStatus == Status.Ok).label("a refreshed token passes /userinfo") &&
              assertTrue(refreshedRoles == Set(roleB, roleC)).label(s"the refreshed token carries the roles held now, got $refreshedRoles")
        yield result
      },
      test("removing a role the user does not hold refuses nothing") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          roleA <- CentralApi.id("rev-role-x")
          roleB <- CentralApi.id("rev-role-y")
          result <- withRoles(auth, List(roleA, roleB)):
            for
              (userId, email) <- freshUser(auth)
              _ <- auth.assignUserRoles(userId, Set(roleA))
              _ <- auth.flushUserOutbox()
              held <- login(s, auth, email)
              _ <- auth.removeUserRoles(userId, Set(roleB))
              _ <- auth.flushUserOutbox()
              // A marker that the removal has been applied by now: the roles the user holds are unchanged.
              status <- userinfoStatus(auth, held.tokens.accessToken)
            yield assertTrue(status == Status.Ok)
        yield result
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(240.seconds)
