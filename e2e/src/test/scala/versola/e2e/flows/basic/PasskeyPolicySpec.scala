package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.test.*

/** The tenant's passkey policy decides what a user without a passkey is shown after signing in.
  *
  * The unit tests prove the decision and the counting; this proves them through the wiring: the policy central stores
  * and syncs to auth, the per-user record auth keeps in Postgres, and the enrolment card the form is rendered from --
  * including that a skip a mandatory card does not offer is refused by the server, not just hidden.
  *
  * The policy is tenant-wide state the rest of the suite signs in under, so every test puts the tenant back on no
  * policy, and the tests run one after another.
  */
object PasskeyPolicySpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  private def policy(mode: String, graceSkips: Option[Int] = None, graceDays: Option[Int] = None): String =
    s"""{"mode":"$mode","graceSkips":${graceSkips.fold("null")(_.toString)},"graceDays":${graceDays.fold("null")(_.toString)}}"""

  private def withPolicy[A](auth: OAuthClient, json: String)(use: => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      auth.upsertChallengeSettings(passkeyPolicy = Some(json)) *> auth.syncConfiguration(),
    )(_ => (auth.upsertChallengeSettings() *> auth.syncConfiguration()).orDie)(_ => use)

  /** Starts an authorization and signs in with login and password; what comes back is the conversation's next step. */
  private def signIn(auth: OAuthClient, s: Flows.Setup) =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      submitted <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
    yield (authorize, cookie, submitted)

  /** Signs in and expects the enrolment card, which is rendered after the submit's redirect. */
  private def signInToCard(auth: OAuthClient, s: Flows.Setup) =
    for
      (authorize, cookie, _) <- signIn(auth, s)
      page <- auth.getChallenge(cookie).assertStep(ConversationStep.PasskeyEnroll)
      card <- page.passkeyEnrollCard
    yield (authorize, cookie, page, card)

  private def enroll(auth: OAuthClient, cookie: String, page: ChallengeResult, card: PasskeyEnrollCard) =
    for
      credential <- TestAuthenticator.create(card.publicKeyOptions, passkeyOrigin)
      submitted <- auth.submitPasskeyEnroll(cookie, credential.responseJson.toJson, "E2E Passkey", page.csrf)
    yield submitted

  def spec = suite("Passkey policy")(
    test("off: signing in finishes without an enrolment card") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        code <- withPolicy(auth, policy("off")):
          signIn(auth, s).flatMap((_, _, submitted) => submitted.assertRedirect)
      yield assertTrue(code.nonEmpty)
    },
    test("offered: the card has a skip button, and skipping completes the sign-in") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- withPolicy(auth, policy("offered")):
          for
            (authorize, cookie, page, card) <- signInToCard(auth, s)
            skipped <- auth.submitPasskeySkip(cookie, page.csrf)
            code <- skipped.assertRedirect
            token <- auth.token(
              code,
              authorize.verifier,
              clientId = Some(s.clientId),
              clientSecret = Some(s.clientSecret),
              redirectUri = Some(s.redirectUri),
            ).success
          yield (card, token)
      yield assertTrue(result._1.skippable, result._2.tokenType.toLowerCase == "bearer")
    },
    test("required: the card has no skip button, a skip is refused, and enrolling completes the sign-in") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- withPolicy(auth, policy("required")):
          for
            (_, cookie, page, card) <- signInToCard(auth, s)
            skipped <- auth.submitPasskeySkip(cookie, page.csrf)
            // The refused skip puts the mandatory card back instead of finishing the sign-in.
            again <- auth.getChallenge(cookie).assertStep(ConversationStep.PasskeyEnroll)
            againCard <- again.passkeyEnrollCard
            enrolled <- enroll(auth, cookie, again, againCard)
            code <- enrolled.assertRedirect
          yield (card, skipped, againCard, code)
      yield assertTrue(
        !result._1.skippable,
        !result._2.location.contains("code="),
        !result._3.skippable,
        result._4.nonEmpty,
      )
    },
    test("required: a user who has enrolled signs in again without being asked") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        code <- withPolicy(auth, policy("required")):
          for
            (_, cookie, page, card) <- signInToCard(auth, s)
            _ <- enroll(auth, cookie, page, card).flatMap(_.assertRedirect)
            (_, _, second) <- signIn(auth, s)
            code <- second.assertRedirect
          yield code
      yield assertTrue(code.nonEmpty)
    },
    test("required_after_grace by skips: skippable until the skips are used up, then mandatory") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- withPolicy(auth, policy("required_after_grace", graceSkips = Some(1))):
          for
            (_, firstCookie, firstPage, firstCard) <- signInToCard(auth, s)
            _ <- auth.submitPasskeySkip(firstCookie, firstPage.csrf).flatMap(_.assertRedirect)
            (_, secondCookie, secondPage, secondCard) <- signInToCard(auth, s)
            refused <- auth.submitPasskeySkip(secondCookie, secondPage.csrf)
            enrolled <- enroll(auth, secondCookie, secondPage, secondCard)
            code <- enrolled.assertRedirect
          yield (firstCard, secondCard, refused, code)
      yield assertTrue(
        result._1.skippable,
        !result._2.skippable,
        !result._3.location.contains("code="),
        result._4.nonEmpty,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
