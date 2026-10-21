package versola.e2e.flows.admin

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** What an administrator takes away through central's users API has to stop working in auth:
  * a deleted user's refresh token, a signed-out user agent's session, a client's revoked
  * offline access. Each test signs a user of its own in -- the shared setups are what every
  * other spec signs in as, and these tests end their access for good.
  */
object UserAccessRevocationSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private val offlineScope = "openid email offline_access"

  private case class Login(tokens: TokenResult.Success, sessionCookie: String)

  /** A user registered through central and known to auth, so the email+OTP client signs them in
    * rather than registering them. */
  private def freshUser(auth: OAuthClient): Task[(String, String)] =
    for
      email <- CentralApi.email("revocation")
      userId <- auth.registerUser(email = Some(email))
      _ <- auth.flushUserOutbox()
    yield (userId.toString, email)

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
      tokens <- auth.token(code, authorize.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
    yield Login(tokens, sessionCookie)

  private def refreshTokenOf(login: Login): Task[String] =
    ZIO.fromOption(login.tokens.refreshToken).orElseFail(RuntimeException("Expected a refresh token for the offline_access scope"))

  private def refresh(s: Flows.Setup, auth: OAuthClient, refreshToken: String): Task[TokenResult] =
    auth.refresh(refreshToken, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret))

  private case class OAuthError(error: String) derives JsonDecoder

  /** The `error` code of a rejected `/token` call. */
  private def rejection(result: TokenResult): Task[String] =
    result match
      case s: TokenResult.Success => ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(_, body) =>
        ZIO.fromEither(body.fromJson[OAuthError]).mapBoth(err => RuntimeException(s"Unparsable /token error body [$err]: $body"), _.error)

  /** Whether the session cookie still signs the user in without interaction. */
  private def silentlyAuthorizes(s: Flows.Setup, auth: OAuthClient, sessionCookie: String): Task[Boolean] =
    auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, scope = Some("openid"), prompt = Some("none"), sessionCookie = Some(sessionCookie))
      .map(_.location.contains("code="))

  private def sessionsOf(auth: OAuthClient, userId: String): Task[Chunk[Json.Obj]] =
    auth.central.get("/users/sessions", "id" -> userId).flatMap(_.objects)

  private def refreshTokensOf(auth: OAuthClient, userId: String): Task[Chunk[Json.Obj]] =
    auth.central.get("/users/refresh-tokens", "id" -> userId).flatMap(_.objects)

  def spec = suite("Admin revocation of a user's access")(
    test("a deleted user's refresh token is refused and their session is gone") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        login <- login(s, auth, email)
        refreshToken <- refreshTokenOf(login)
        deleted <- auth.central.delete("/users", "id" -> userId)
        error <- refresh(s, auth, refreshToken).flatMap(rejection)
        signedIn <- silentlyAuthorizes(s, auth, login.sessionCookie)
        found <- auth.central.get("/users", "id" -> userId).flatMap(_.items("users"))
        again <- auth.central.delete("/users", "id" -> userId)
      yield assertTrue(deleted.status == Status.NoContent) &&
        assertTrue(error == "invalid_grant").label("the refresh token of a user who no longer exists must not redeem") &&
        assertTrue(!signedIn).label("the session cookie of a deleted user must not sign anyone in") &&
        assertTrue(found.isEmpty).label("the index must no longer resolve the id") &&
        assertTrue(again.status == Status.NotFound).label("a second deletion names a user that no longer exists")
    },
    test("deleting an unknown user answers 404") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        id <- CentralApi.uuid
        rejected <- auth.central.delete("/users", "id" -> id.toString)
      yield assertTrue(rejected.status == Status.NotFound)
    },
    test("signing one user agent out ends that session and leaves the other") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        first <- login(s, auth, email)
        second <- login(s, auth, email)
        before <- sessionsOf(auth, userId)
        target <- ZIO.fromOption(before.headOption.flatMap(_.str("publicId"))).orElseFail(RuntimeException(s"No session listed: $before"))
        ended <- auth.central.delete("/users/sessions", "id" -> userId, "sessionId" -> target)
        after <- sessionsOf(auth, userId)
        firstAlive <- silentlyAuthorizes(s, auth, first.sessionCookie)
        secondAlive <- silentlyAuthorizes(s, auth, second.sessionCookie)
        unknown <- auth.central.delete("/users/sessions", "id" -> userId, "sessionId" -> target)
      yield assertTrue(before.size == 2, ended.status == Status.NoContent) &&
        assertTrue(after.size == 1).label("one session must be listed after one was ended") &&
        assertTrue(firstAlive != secondAlive).label("exactly one of the two user agents must still be signed in") &&
        assertTrue(unknown.status == Status.NotFound).label("a session that was already ended is no longer the user's to end")
    },
    test("revoking a client's offline access refuses its refresh token but keeps the user signed in") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        login <- login(s, auth, email)
        refreshToken <- refreshTokenOf(login)
        listed <- refreshTokensOf(auth, userId)
        revoked <- auth.central.delete("/users/refresh-tokens", "id" -> userId, "clientId" -> s.clientId)
        error <- refresh(s, auth, refreshToken).flatMap(rejection)
        signedIn <- silentlyAuthorizes(s, auth, login.sessionCookie)
        remaining <- refreshTokensOf(auth, userId)
      yield assertTrue(listed.exists(_.str("clientId").contains(s.clientId))).label("the grant must be listed before it is revoked") &&
        assertTrue(revoked.status == Status.NoContent) &&
        assertTrue(error == "invalid_grant").label("the revoked refresh token must not redeem") &&
        assertTrue(signedIn).label("revoking offline access is not a logout: the session stays") &&
        assertTrue(!remaining.exists(_.str("clientId").contains(s.clientId))).label("the listing must no longer show the client's grant")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
