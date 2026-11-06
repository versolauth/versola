package versola.e2e.flows.revocation

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** What an administrator's change to a user does to the access tokens the user already holds: a password
  * set or reset, the revoking of a client's offline access, the removal of a role. `auth` refuses those
  * tokens at `/userinfo` by itself (the client registered no back channel), and each case checks what is
  * meant to survive as well as what is meant to stop: the refresh token, which is how the user gets a
  * token that reflects the change, and other users' tokens.
  *
  * Every test signs a user of its own in: these tests end that user's access.
  *
  * `iat` is whole seconds and the boundary second belongs to the revocation, so a token minted in the
  * second of the change is refused as well. Where a test needs a token that is accepted afterwards it
  * refreshes until one is, within a bound, rather than guessing the second.
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

  /** Refreshes, and refreshes again with the token that came back, until `/userinfo` accepts the access token
    * it yielded: the first may have been minted in the second of the change, which counts as before it. Each
    * round is a refresh the server accepted, which is the point; the bound keeps a broken refusal from looping.
    * Returns the accepted tokens. */
  private def refreshUntilAccepted(s: Flows.Setup, auth: OAuthClient, refreshToken: String): Task[TokenResult.Success] =
    def attempt(token: String, left: Int): Task[TokenResult.Success] =
      for
        refreshed <- auth.refresh(token, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).success
        status <- userinfoStatus(auth, refreshed.accessToken)
        next <-
          if status == Status.Ok then ZIO.succeed(refreshed)
          else if left <= 0 then ZIO.fail(RuntimeException(s"/userinfo kept refusing refreshed tokens, last status $status"))
          else
            ZIO.fromOption(refreshed.refreshToken).orElseFail(RuntimeException("the refresh response carried no refresh token"))
              .flatMap(rotated => attempt(rotated, left - 1).delay(300.millis))
      yield next
    attempt(refreshToken, left = 20)

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
          // Returns once the second of the change is over (see `setUserPassword`).
          _ <- auth.setUserPassword(userId, "Admin-Set-Pass-1!")
          after <- awaitRefused(auth, held.tokens.accessToken)
          refreshed <- refreshUntilAccepted(s, auth, refreshToken)
          signedIn <- silentlyAuthorizes(s, auth, held.sessionCookie)
          bystanderAfter <- userinfoStatus(auth, bystander.tokens.accessToken)
        yield assertTrue(before == Status.Ok).label("the token works until the password is set") &&
          assertTrue(after == Status.Unauthorized).label("a token issued before an administrator set the password is refused") &&
          assertTrue(refreshed.response.status == Status.Ok).label("the refresh token is not touched: it yields a token that works") &&
          assertTrue(signedIn).label("the session is not ended by a password set") &&
          assertTrue(bystanderAfter == Status.Ok).label("another user's token is not affected")
      },
    ),
    suite("password reset")(
      test("refuses the tokens issued before it, and lets the user refresh into one that works") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          (userId, email) <- freshUser(auth)
          held <- login(s, auth, email)
          refreshToken <- refreshTokenOf(held)
          before <- userinfoStatus(auth, held.tokens.accessToken)
          _ <- auth.resetUserPassword(userId)
          after <- awaitRefused(auth, held.tokens.accessToken)
          refreshed <- refreshUntilAccepted(s, auth, refreshToken)
        yield assertTrue(before == Status.Ok) &&
          assertTrue(after == Status.Unauthorized).label("a token issued before an administrator reset the password is refused") &&
          assertTrue(refreshed.response.status == Status.Ok).label("the refresh token still redeems")
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
              _ <- auth.removeUserRoles(userId, Set(roleA))
              _ <- auth.flushUserOutbox()
              afterRemove <- awaitRefused(auth, held.tokens.accessToken)
              // `/token` does not ask the list: the refresh token is how the user gets a token without the role.
              refreshed <- refreshUntilAccepted(s, auth, refreshToken)
              refreshedRoles <- rolesOf(refreshed.accessToken)
            yield assertTrue(heldRoles == Set(roleA, roleB)) &&
              assertTrue(afterAdd == Status.Ok).label("adding a role must not refuse the tokens issued before it") &&
              assertTrue(afterRemove == Status.Unauthorized).label("a token issued before a role was removed must be refused") &&
              assertTrue(refreshed.response.status == Status.Ok).label("a refreshed token passes /userinfo") &&
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
