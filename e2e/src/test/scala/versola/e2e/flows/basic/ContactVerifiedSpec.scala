package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

import java.util.UUID

/** A confirmed one-time code marks the contact it was sent to as verified, and userinfo reports it.
  *
  * The unit and repository tests prove the rule against stubs and against Postgres; this proves it
  * survives the wiring: the user synced from Central, the OTP step, the `UPDATE` in auth's own
  * database, and the `email_verified` / `phone_number_verified` claims read back over HTTP. A
  * contact starts out unverified, so a `true` can only come from the sign-in under test. The
  * failure paths (wrong code, expired, rate-limited) leave no session to read a claim with, so they
  * are covered where they can be observed: `OtpConversationServiceSpec`.
  */
object ContactVerifiedSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  /** An unused German mobile number. `prefix` is a real mobile range (151, 152): a made-up one such
    * as 153 is rejected as not a number, and the flow never leaves the credential step. */
  private def unusedPhone(prefix: String): String =
    val uid = UUID.randomUUID()
    f"+49$prefix${uid.getLeastSignificantBits.abs % 100_000_000L}%08d"

  private def unusedEmail: String = s"verified-${UUID.randomUUID().toString.take(12)}@example.test"

  /** Walks the sign-in, submitting `contact` (an email or a phone) and the fixed OTP, and returns
    * what userinfo says about the user it ended with. */
  private def signIn(
      auth: OAuthClient,
      s: Flows.Setup,
      scope: String,
      contact: Either[String, String],
  ): Task[UserinfoResult.Success] =
    for
      authorize <- auth.authorize(scope = scope, clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- contact match
        case Left(email) => auth.submitEmail(cookie, email, credential.csrf).unit
        case Right(phone) => auth.submitPhone(cookie, phone, credential.csrf).unit
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      userinfo <- auth.userinfo(token.accessToken).success
    yield userinfo

  def spec = suite("Contact verified after OTP")(
    test("an existing user's email is verified once its OTP is confirmed, and stays so on the next sign-in") {
      val email = unusedEmail
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        _ <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        first <- signIn(auth, s, "openid email", Left(email))
        second <- signIn(auth, s, "openid email", Left(email))
      yield assertTrue(
        first.email.contains(email),
        first.emailVerified.contains(true),
        second.sub == first.sub,
        second.emailVerified.contains(true),
      ).label("a confirmed email OTP must report email_verified=true, and a repeat must leave it so")
    },
    test("an existing user's phone is verified once its OTP is confirmed") {
      val phone = unusedPhone("151")
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        _ <- auth.registerUser(phone = Some(phone))
        _ <- auth.flushUserOutbox()
        userinfo <- signIn(auth, s, "openid phone", Right(phone))
      yield assertTrue(
        userinfo.phoneNumber.contains(phone),
        userinfo.phoneVerified.contains(true),
      ).label("a confirmed phone OTP must report phone_number_verified=true")
    },
    test("an email that registers through the OTP is verified from the start") {
      val email = unusedEmail
      for
        (s, auth) <- setup(Flows.Id.EmailRegistration)
        userinfo <- signIn(auth, s, "openid email", Left(email))
      yield assertTrue(
        userinfo.email.contains(email),
        userinfo.emailVerified.contains(true),
      ).label("registration is the OTP itself, so the new account's email must be verified")
    },
    test("a phone that registers through the OTP is verified from the start") {
      val phone = unusedPhone("152")
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        userinfo <- signIn(auth, s, "openid phone", Right(phone))
      yield assertTrue(
        userinfo.phoneNumber.contains(phone),
        userinfo.phoneVerified.contains(true),
      ).label("registration is the OTP itself, so the new account's phone must be verified")
    },
  )
