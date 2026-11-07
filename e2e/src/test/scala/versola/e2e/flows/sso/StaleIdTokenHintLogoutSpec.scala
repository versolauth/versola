package versola.e2e.flows.sso

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Response, Status}
import zio.test.*

/** OIDC RP-Initiated Logout 1.0 §2 over HTTP against the staged stack: a logout request whose `id_token_hint` does not belong to the
  * session the browser's cookie names is put to the user, and ends nothing by itself.
  *
  * Every sign-in issues a new session, so the ID token an RP kept from before a step-up or re-authentication carries the `sid` of a session
  * that is gone, while the browser's cookie names the new one. Auth used to answer such a request with the signed-out page and leave the
  * session alive; it now shows the confirmation page, and the session ends only when the user confirms. A hint that does belong to the
  * cookie's session is still acted on at once.
  */
object StaleIdTokenHintLogoutSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  /** A sign-in with email + code that returns the `SSO_SESSION` cookie it creates and the ID token it issues. With `sessionCookie`
    * the sign-in is a re-authentication (`prompt=login`) of a browser that already holds that session. */
  private def signIn(s: Flows.Setup, auth: OAuthClient, sessionCookie: Option[String]): Task[(String, String)] =
    for
      authorize <- auth.authorizeRaw(
        clientId = s.clientId,
        redirectUri = s.redirectUri,
        sessionCookie = sessionCookie,
        prompt = sessionCookie.map(_ => "login"),
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, s.email.get, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      code <- ZIO.succeed(submit).assertRedirect(auth, cookie)
      session <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("no SSO_SESSION cookie after the sign-in"))
      tokens <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(tokens.idToken).orElseFail(RuntimeException("expected an id_token for the openid scope"))
    yield (session, idToken)

  /** Which page the logout endpoint answered with. Both pages carry their step's fields in the form config the page inlines: the
    * confirmation page's own `csrfToken`, the signed-out page's `logoutUris`. */
  private def pageKind(response: Response): Task[Option[String]] =
    response.body.asString.map: html =>
      if html.contains("\"logoutUris\"") then Some("signed-out")
      else if html.contains("\"csrfToken\"") then Some("confirm-logout")
      else None

  private def clearsSessionCookie(response: Response): Boolean =
    response.headers.toList.exists(h => h.headerName.equalsIgnoreCase("set-cookie") && h.renderedValue.startsWith("SSO_SESSION="))

  /** Whether a request carrying the cookie is still answered with a code, without a login: the session is alive. */
  private def sessionAlive(s: Flows.Setup, auth: OAuthClient, sessionCookie: String): Task[Boolean] =
    auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, sessionCookie = Some(sessionCookie)).assertCodeRedirect
      .as(true).orElseSucceed(false)

  def spec = suite("RP-initiated logout with an id_token_hint of another session")(
    test("asks for confirmation instead of saying the user is signed out, and the session stays") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (firstSession, staleIdToken) <- signIn(s, auth, None)
        (currentSession, _) <- signIn(s, auth, Some(firstSession))
        response <- auth.logoutWithSessionAndHint(currentSession, staleIdToken)
        step <- pageKind(response)
        clearsCookie = clearsSessionCookie(response)
        alive <- sessionAlive(s, auth, currentSession)
      yield assertTrue(response.status == Status.Ok, step.contains("confirm-logout"), !clearsCookie, alive)
    },
    test("a hint of the cookie's own session ends it at once, without asking") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (firstSession, _) <- signIn(s, auth, None)
        (currentSession, currentIdToken) <- signIn(s, auth, Some(firstSession))
        response <- auth.logoutWithSessionAndHint(currentSession, currentIdToken)
        step <- pageKind(response)
        clearsCookie = clearsSessionCookie(response)
        alive <- sessionAlive(s, auth, currentSession)
      yield assertTrue(response.status == Status.Ok, step.contains("signed-out"), clearsCookie, !alive)
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
