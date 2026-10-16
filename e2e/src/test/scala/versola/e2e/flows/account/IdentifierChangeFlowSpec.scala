package versola.e2e.flows.account

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

import java.util.UUID

/** Changing a phone number or email from the account page (#634): the page records an intent,
  * and the change itself runs through `/authorize` and the ordinary conversation -- the old
  * identifier again, then a code to the new one, then the move in central and auth.
  *
  * Every test makes its own user: a change one test makes must not be the starting point of the next.
  */
object IdentifierChangeFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"
  private val wrongOtp = "000000"
  private val scope = "openid email phone"

  private case class Account(setup: Flows.Setup, email: String, phone: String):
    def caller: AccountCaller = AccountCaller(setup.userId, setup.clientId, "unused")

  // Not ZIO's Random: under the test environment it is seeded, so every test would draw the same
  // phone and email and the second one to register would be refused as a duplicate.
  private def freshPhone: UIO[String] =
    ZIO.succeed(f"+49152${UUID.randomUUID().getLeastSignificantBits.abs % 100_000_000L}%08d")

  private def freshEmail: UIO[String] =
    ZIO.succeed(s"change-${UUID.randomUUID().toString.take(8)}@example.test")

  /** A user with an email and a phone, on the shared client that signs in by email and OTP. */
  private def newAccount(auth: OAuthClient, shared: Flows.Setup): Task[Account] =
    for
      email <- freshEmail
      phone <- freshPhone
      userId <- auth.registerUser(email = Some(email), phone = Some(phone))
      _ <- auth.flushUserOutbox()
    yield Account(shared.copy(userId = userId, email = Some(email), phone = Some(phone)), email, phone)

  /** Signs in by email and OTP and returns the SSO_SESSION cookie. */
  private def signIn(auth: OAuthClient, account: Account): Task[String] =
    val s = account.setup
    for
      authorize <- auth.authorize(scope = scope, clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie)
      _ <- auth.submitEmail(cookie, account.email, credential.csrf)
      otp <- auth.getChallenge(cookie)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      _ <- ZIO.succeed(submit).assertRedirect(auth, cookie)
      session <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie after signing in"))
    yield session

  /** The authorization request the account page's redirect makes. */
  private def startChange(
      auth: OAuthClient,
      account: Account,
      session: Option[String],
      acr: String,
      hint: String,
  ): Task[AuthorizeResult] =
    auth.authorizeRaw(
      clientId = account.setup.clientId,
      redirectUri = account.setup.redirectUri,
      scope = Some(scope),
      sessionCookie = session,
      acrValues = Some(acr),
      loginHint = Some(hint),
    )

  /** Proves the old identifier, which the change asks for again however fresh the session is. */
  private def proveOldIdentifier(auth: OAuthClient, cookie: String): Task[Unit] =
    for
      old <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      _ <- auth.submitOtp(cookie, fixedOtp, old.csrf)
    yield ()

  private def userinfoAfter(auth: OAuthClient, account: Account, started: AuthorizeResult, code: String): Task[UserinfoResult.Success] =
    for
      token <- auth.token(
        code,
        started.verifier,
        clientId = Some(account.setup.clientId),
        clientSecret = Some(account.setup.clientSecret),
        redirectUri = Some(account.setup.redirectUri),
      ).success
      info <- auth.userinfo(token.accessToken).success
    yield info

  /** What the user has now, read through a fresh sign-in. */
  private def userinfoOf(auth: OAuthClient, account: Account): Task[UserinfoResult.Success] =
    for
      session <- signIn(auth, account)
      silent <- auth.authorizeRaw(
        clientId = account.setup.clientId,
        redirectUri = account.setup.redirectUri,
        scope = Some(scope),
        sessionCookie = Some(session),
      )
      code <- silent.assertCodeRedirect
      info <- userinfoAfter(auth, account, silent, code)
    yield info

  def spec = suite("Identifier change (account page)")(
    test("changes the phone: the old identifier again, a code to the new phone, then the new phone is the user's") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newPhone <- freshPhone
        session <- signIn(auth, account)
        intent <- auth.createIdentifierChangeIntent(account.caller, "phone", newPhone)
        started <- startChange(auth, account, Some(session), Acr.PhoneChange, newPhone).assertChallengeRedirect
        cookie = started.conversationCookie.get
        _ <- proveOldIdentifier(auth, cookie)
        // The second code goes to the new phone, in the same dialog.
        newOtp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, newOtp.csrf).assertRedirect(auth, cookie)
        info <- userinfoAfter(auth, account, started, code)
      yield assertTrue(intent.status == Status.NoContent) &&
        assertTrue(info.phoneNumber.contains(newPhone)).label("the id token of the same request already carries the new phone") &&
        assertTrue(info.email.contains(account.email)).label("the other identifier is untouched")
    },
    test("changes the email the same way") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newEmail <- freshEmail
        session <- signIn(auth, account)
        _ <- auth.createIdentifierChangeIntent(account.caller, "email", newEmail)
        started <- startChange(auth, account, Some(session), Acr.EmailChange, newEmail).assertChallengeRedirect
        cookie = started.conversationCookie.get
        _ <- proveOldIdentifier(auth, cookie)
        newOtp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, newOtp.csrf).assertRedirect(auth, cookie)
        info <- userinfoAfter(auth, account, started, code)
      yield assertTrue(info.email.contains(newEmail)) &&
        assertTrue(info.phoneNumber.contains(account.phone)).label("the other identifier is untouched")
    },
    test("a wrong code for the new phone changes nothing") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newPhone <- freshPhone
        session <- signIn(auth, account)
        _ <- auth.createIdentifierChangeIntent(account.caller, "phone", newPhone)
        started <- startChange(auth, account, Some(session), Acr.PhoneChange, newPhone).assertChallengeRedirect
        cookie = started.conversationCookie.get
        _ <- proveOldIdentifier(auth, cookie)
        newOtp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, wrongOtp, newOtp.csrf)
        again <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        info <- userinfoOf(auth, account)
      yield assertTrue(again.step.contains(ConversationStep.Otp)).label("still asking for the code") &&
        assertTrue(info.phoneNumber.contains(account.phone)).label("the phone was not changed")
    },
    test("a phone another user holds is refused on the page, before the browser leaves it") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        other <- newAccount(auth, shared)
        taken <- auth.createIdentifierChangeIntent(account.caller, "phone", other.phone)
        info <- userinfoOf(auth, account)
      yield assertTrue(taken.status == Status.Conflict) &&
        assertTrue(info.phoneNumber.contains(account.phone)).label("the phone was not changed")
    },
    test("is refused without an intent from the account page") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newPhone <- freshPhone
        session <- signIn(auth, account)
        refused <- startChange(auth, account, Some(session), Acr.PhoneChange, newPhone).assertErrorRedirect("access_denied")
      yield assertTrue(true)
    },
    test("an intent starts one change only") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newPhone <- freshPhone
        session <- signIn(auth, account)
        _ <- auth.createIdentifierChangeIntent(account.caller, "phone", newPhone)
        _ <- startChange(auth, account, Some(session), Acr.PhoneChange, newPhone).assertChallengeRedirect
        replay <- startChange(auth, account, Some(session), Acr.PhoneChange, newPhone).assertErrorRedirect("access_denied")
      yield assertTrue(true)
    },
    test("an intent for one phone does not start a change to another") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        named <- freshPhone
        other <- freshPhone
        session <- signIn(auth, account)
        _ <- auth.createIdentifierChangeIntent(account.caller, "phone", named)
        swapped <- startChange(auth, account, Some(session), Acr.PhoneChange, other).assertErrorRedirect("access_denied")
      yield assertTrue(true)
    },
    test("needs a signed-in user") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        newPhone <- freshPhone
        _ <- auth.createIdentifierChangeIntent(account.caller, "phone", newPhone)
        none <- startChange(auth, account, None, Acr.PhoneChange, newPhone).assertErrorRedirect("login_required")
      yield assertTrue(true)
    },
    test("refuses a login_hint that is not a phone for a phone change") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        session <- signIn(auth, account)
        refused <- startChange(auth, account, Some(session), Acr.PhoneChange, "not-a-phone").assertErrorRedirect("invalid_request")
      yield assertTrue(true)
    },
    test("the account page's intent endpoint rejects a bad value, the user's own identifier, and unauthenticated calls") {
      for
        (shared, auth) <- setup(Flows.Id.IdentifierChange)
        account <- newAccount(auth, shared)
        invalid <- auth.createIdentifierChangeIntent(account.caller, "phone", "not-a-phone")
        unchanged <- auth.createIdentifierChangeIntent(account.caller, "email", account.email)
        unknownKind <- auth.createIdentifierChangeIntent(account.caller, "login", "someone")
        anonymous <- auth.createIdentifierChangeIntent(account.caller, "phone", "+4915200000000", credentials = None)
      yield assertTrue(invalid.status == Status.BadRequest) &&
        assertTrue(unchanged.status == Status.Conflict) &&
        assertTrue(unknownKind.status == Status.BadRequest) &&
        assertTrue(anonymous.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
