package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

import java.util.UUID

/** The tenant's email registration policy decides whether an unknown address gets an account.
  *
  * The unit tests prove the matching rule; this proves it survives the wiring: the policy
  * central validates and stores, the sync channel to auth, and the check auth makes when an
  * address it does not know completes the OTP step. A refused address is not told so up front --
  * it walks the ordinary sign-in path, where the OTP it was sent can never be right and no
  * account appears.
  *
  * The settings are deployment-wide state the rest of the suite reads, so every test puts the
  * tenant back on the suite's `open` policy -- and the tests run one after another, because they
  * all write that same state.
  */
object EmailRegistrationSpec extends E2ESpec:

  private val fixedOtp = "123456"

  private def policy(mode: String, domains: List[String] = Nil, blockSubaddress: Boolean = false): String =
    val list = domains.map(d => s""""$d"""").mkString("[", ",", "]")
    s"""{"mode":"$mode","domains":$list,"blockSubaddress":$blockSubaddress}"""

  private def withPolicy[A](auth: OAuthClient, emailRegistration: String)(use: => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      auth.upsertChallengeSettings(emailRegistration = emailRegistration) *> auth.syncConfiguration(),
    )(_ => (auth.upsertChallengeSettings() *> auth.syncConfiguration()).orDie)(_ => use)

  private def newEmail(domain: String): String = s"reg-${UUID.randomUUID().toString.take(12)}@$domain"

  /** Walks the email sign-in with the fake OTP; `true` when the address ended up with an account. */
  private def registers(auth: OAuthClient, s: Flows.Setup, email: String): Task[Boolean] =
    for
      authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, email, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      // Only a completed registration leaves the challenge for the client's redirect; a refused
      // address is turned back at the OTP step, so the redirect is what tells the two apart.
      redirected <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect(auth, cookie).as(true).orElseSucceed(false)
      exists <- auth.userExistsByEmail(email)
    yield redirected && exists

  def spec = suite("Email registration")(
    test("closed: an unknown email does not register") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        registered <- withPolicy(auth, policy("closed"))(registers(auth, s, newEmail("example.test")))
      yield assertTrue(!registered)
        .label("a tenant whose email registration is closed must not create an account for an unknown address")
    },
    test("allowlist: a listed domain registers and an unlisted one does not") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (listed, unlisted) <- withPolicy(auth, policy("allowlist", List("example.test"))):
          registers(auth, s, newEmail("example.test")).zip(registers(auth, s, newEmail("other.test")))
      yield assertTrue(listed, !unlisted)
    },
    test("allowlist: a *.domain entry admits its subdomains") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (sub, other) <- withPolicy(auth, policy("allowlist", List("*.example.test"))):
          registers(auth, s, newEmail("mail.example.test")).zip(registers(auth, s, newEmail("other.test")))
      yield assertTrue(sub, !other)
    },
    test("open: a blocked domain does not register and any other does") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (blocked, free) <- withPolicy(auth, policy("open", List("blocked.test"))):
          registers(auth, s, newEmail("blocked.test")).zip(registers(auth, s, newEmail("example.test")))
      yield assertTrue(!blocked, free)
    },
    test("open: blockSubaddress rejects a local part with a + and keeps a plain one") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (tagged, plain) <- withPolicy(auth, policy("open", blockSubaddress = true)):
          registers(auth, s, s"tag+${UUID.randomUUID().toString.take(8)}@example.test")
            .zip(registers(auth, s, newEmail("example.test")))
      yield assertTrue(!tagged, plain)
    },
    test("central refuses a domain entry that is not a domain") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        result <- auth.upsertChallengeSettings(emailRegistration = policy("allowlist", List("not a domain"))).either
      yield assertTrue(result.left.exists(_.getMessage.contains("BadRequest")))
    },
    test("central refuses an empty defaultRoles") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        result <- auth.upsertChallengeSettings(defaultRoles = "[]").either
      yield assertTrue(result.left.exists(_.getMessage.contains("BadRequest")))
    },
  ) @@ TestAspect.sequential
