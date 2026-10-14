package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** The countries a tenant lists in central are what its phone sign-in takes.
  *
  * The unit tests prove the matching rule; this proves it survives the wiring: the regions
  * central stores as JSON, the sync channel to auth, the form configuration the credential step
  * is rendered with, and the check auth repeats on the submitted number whatever the form did.
  *
  * The settings are deployment-wide state the rest of the suite reads, so every test puts the
  * tenant back on no regions at all -- and the tests run one after another, because they all
  * write that same state.
  */
object PhoneRegionsSpec extends E2ESpec:

  private def region(code: String, dialCode: String, length: Int, regex: String, registration: Boolean = true): String =
    s"""{"region":"$code","dialCode":"$dialCode","length":$length,"regex":"${regex.replace("\\", "\\\\")}","registration":$registration}"""

  // Flows.setupPhoneOtp registers a German mobile number: +49 151 and eight more digits.
  private val de = region("DE", "+49", 11, "15\\d{9}")
  private val deNoRegistration = region("DE", "+49", 11, "15\\d{9}", registration = false)
  private val kz = region("KZ", "+7", 10, "7\\d{9}")

  private def withRegions[A](auth: OAuthClient, regions: String*)(use: => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      auth.upsertChallengeSettings(phoneRegions = regions.mkString("[", ",", "]")) *> auth.syncConfiguration(),
    )(_ => (auth.upsertChallengeSettings() *> auth.syncConfiguration()).orDie)(_ => use)

  def spec = suite("Phone regions")(
    test("the credential step carries the tenant's regions in the order central stores them") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        challenge <- withRegions(auth, kz, de) {
          for
            authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
            challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
          yield challenge
        }
        kzAt = challenge.html.indexOf(""""region":"KZ"""")
        deAt = challenge.html.indexOf(""""region":"DE"""")
      yield assertTrue(kzAt >= 0, deAt > kzAt)
    },
    test("a number inside a listed region goes on to the OTP step") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        phone = s.phone.get
        result <- withRegions(auth, kz, de) {
          for
            authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
            cookie = authorize.conversationCookie.get
            challenge <- auth.getChallenge(cookie)
            submit <- auth.submitPhone(cookie, phone, challenge.csrf)
            next <- auth.getChallenge(cookie)
          yield (submit.response.status, next.step)
        }
      yield assertTrue(result._1 != Status.BadRequest, result._2 == Some(ConversationStep.Otp))
    },
    test("a number outside every listed region is refused even when the form is bypassed") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        status <- withRegions(auth, kz) {
          for
            authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
            cookie = authorize.conversationCookie.get
            challenge <- auth.getChallenge(cookie)
            submit <- auth.submitPhone(cookie, s.phone.get, challenge.csrf)
            next <- auth.getChallenge(cookie)
          yield (submit.response.status, next.step)
        }
      yield assertTrue(status._1 == Status.BadRequest, status._2 == Some(ConversationStep.Credential))
    },
    test("a new number registers only when its region allows registration") {
      def newNumber = f"+49151${java.util.UUID.randomUUID().getLeastSignificantBits.abs % 100_000_000L}%08d"
      def attempt(auth: OAuthClient, s: Flows.Setup, phone: String) =
        for
          authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          cookie = authorize.conversationCookie.get
          challenge <- auth.getChallenge(cookie)
          _ <- auth.submitPhone(cookie, phone, challenge.csrf)
          otp <- auth.getChallenge(cookie)
          _ <- auth.submitOtp(cookie, "123456", otp.csrf)
          next <- auth.getChallenge(cookie)
        yield next.step
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        closed <- withRegions(auth, deNoRegistration)(attempt(auth, s, newNumber))
        open <- withRegions(auth, de)(attempt(auth, s, newNumber))
      yield assertTrue(closed != Some(ConversationStep.SetPassword), open == Some(ConversationStep.SetPassword))
    },
    test("central refuses a region whose regex does not compile") {
      for
        (_, auth) <- setup(Flows.Id.PhoneOtp)
        result <- auth.upsertChallengeSettings(phoneRegions = s"[${region("KZ", "+7", 10, "[7")}]").either
      yield assertTrue(result.left.exists(_.getMessage.contains("BadRequest")))
    },
  ) @@ TestAspect.sequential
