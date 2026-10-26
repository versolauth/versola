package versola.antifraud

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.model.AuthId
import versola.util.{Base64, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object PowChallengeVerifierSpec extends UnitSpecBase:

  private val subject = ChallengeSubject(
    tenantId = Some(TenantId("acme")),
    clientId = ClientId("client-a"),
    conversationId = AuthId(UUID.fromString("018f2a4c-0000-7000-8000-000000000001")),
    subjectHash = "subject-hash",
  )

  private val settings = PowChallengeVerifier.Settings(
    enabled = true,
    minDifficulty = 8,
    maxDifficulty = 12,
    scoreStep = 10,
    ttl = 2.minutes,
    maxAttempts = 4,
  )

  private def verifier(s: PowChallengeVerifier.Settings = settings): UIO[ChallengeVerifier] =
    PowChallengeVerifier.live.build.map(_.get[ChallengeVerifier]).provideSome[Scope](
      ZLayer.succeed(s),
      ZLayer.succeed(AntifraudEvents.Settings.default.copy(decision = Antifraud.Settings(enabled = true, challengeAt = 40))),
      ZLayer.succeed(TestEnvConfig.coreConfig),
      SecureRandom.live,
      SecureRandom.live >>> SecurityService.live,
    ).provide(Scope.default)

  /** What a client does: counts up until the hash has enough zero bits. */
  private def solve(challenge: Challenge): String =
    val seed = challenge.params("seed")
    val difficulty = challenge.params("difficulty").toInt
    Iterator.from(0).map(_.toString).find: nonce =>
      PowChallengeVerifier.leadingZeroBits(
        MessageDigest.getInstance("SHA-256").digest(s"$seed:$nonce".getBytes(StandardCharsets.UTF_8)),
      ) >= difficulty
    .get

  /** A nonce that does not solve it. */
  private def wrongNonce(challenge: Challenge): String =
    val seed = challenge.params("seed")
    val difficulty = challenge.params("difficulty").toInt
    Iterator.from(0).map(_.toString).find: nonce =>
      PowChallengeVerifier.leadingZeroBits(MessageDigest.getInstance("SHA-256").digest(s"$seed:$nonce".getBytes(StandardCharsets.UTF_8))) < difficulty
    .get

  def spec = suite("PowChallengeVerifier")(
    suite("difficulty")(
      test("starts at the minimum for a request that only just reached the threshold, and grows a bit per step of score") {
        assertTrue(
          PowChallengeVerifier.difficultyFor(settings, challengeAt = 40, score = 40) == 8,
          PowChallengeVerifier.difficultyFor(settings, challengeAt = 40, score = 59) == 9,
          PowChallengeVerifier.difficultyFor(settings, challengeAt = 40, score = 60) == 10,
        )
      },
      test("never goes below the minimum or above the maximum, and a failure's score of 0 is the minimum") {
        assertTrue(
          PowChallengeVerifier.difficultyFor(settings, challengeAt = 40, score = 0) == 8,
          PowChallengeVerifier.difficultyFor(settings, challengeAt = 40, score = 10000) == 12,
          PowChallengeVerifier.difficultyFor(settings.copy(scoreStep = 0), challengeAt = 40, score = 10000) == 8,
        )
      },
      test("counts leading zero bits") {
        assertTrue(
          PowChallengeVerifier.leadingZeroBits(Array(0x00, 0x00, 0x01).map(_.toByte)) == 23,
          PowChallengeVerifier.leadingZeroBits(Array(0x0f).map(_.toByte)) == 4,
          PowChallengeVerifier.leadingZeroBits(Array(0x80.toByte)) == 0,
        )
      },
    ),
    suite("a challenge")(
      test("is issued at the difficulty its score asks for and solved by finding the nonce") {
        ZIO.scoped:
          for
            v <- verifier()
            issued <- v.issue(subject, score = 60)
            challenge = issued.get
            result <- v.verify(subject, challenge.token, solve(challenge))
          yield assertTrue(challenge.kind == "pow", challenge.level == 10, challenge.params("difficulty") == "10", result == ChallengeResult.Passed)
      },
      test("is not solved by a nonce that does not reach its difficulty") {
        ZIO.scoped:
          for
            v <- verifier()
            challenge <- v.issue(subject, score = 60).map(_.get)
            result <- v.verify(subject, challenge.token, wrongNonce(challenge))
          yield assertTrue(result == ChallengeResult.Failed)
      },
      test("is not solved by anything that is not a short decimal number") {
        ZIO.scoped:
          for
            v <- verifier()
            challenge <- v.issue(subject, score = 60).map(_.get)
            results <- ZIO.foreach(List("", "abc", "-1", "1e3", "1" * 21, " 5"))(v.verify(subject, challenge.token, _))
          yield assertTrue(results.forall(_ == ChallengeResult.Failed))
      },
      test("is only good for the request and destination it was issued for") {
        ZIO.scoped:
          for
            v <- verifier()
            challenge <- v.issue(subject, score = 60).map(_.get)
            nonce = solve(challenge)
            otherConversation <- v.verify(subject.copy(conversationId = AuthId(UUID.randomUUID())), challenge.token, nonce)
            otherSubject <- v.verify(subject.copy(subjectHash = "another"), challenge.token, nonce)
            otherClient <- v.verify(subject.copy(clientId = ClientId("client-b")), challenge.token, nonce)
            otherTenant <- v.verify(subject.copy(tenantId = None), challenge.token, nonce)
          yield assertTrue(List(otherConversation, otherSubject, otherClient, otherTenant).forall(_ == ChallengeResult.Failed))
      },
      test("cannot be made easier: a token whose difficulty is edited, or whose signature is another's, is refused") {
        ZIO.scoped:
          for
            v <- verifier()
            challenge <- v.issue(subject, score = 60).map(_.get)
            Array(body, signature) = challenge.token.split('.')
            edited = new String(Base64.urlDecode(body), StandardCharsets.UTF_8).replace("\"difficulty\":10", "\"difficulty\":0")
            forged = s"${Base64.urlEncode(edited.getBytes(StandardCharsets.UTF_8))}.$signature"
            easy <- v.verify(subject, forged, "0")
            garbage <- ZIO.foreach(List("", "x", "a.b", "a.b.c", s"$body.", s".$signature"))(v.verify(subject, _, "0"))
          yield assertTrue(easy == ChallengeResult.Failed, garbage.forall(_ == ChallengeResult.Failed))
      },
      test("is refused as expired once its time to live has passed, and not before") {
        ZIO.scoped:
          for
            v <- verifier()
            challenge <- v.issue(subject, score = 60).map(_.get)
            nonce = solve(challenge)
            _ <- TestClock.adjust(119.seconds)
            inTime <- v.verify(subject, challenge.token, nonce)
            _ <- TestClock.adjust(2.seconds)
            late <- v.verify(subject, challenge.token, nonce)
          yield assertTrue(inTime == ChallengeResult.Passed, late == ChallengeResult.Expired)
      },
      test("is different every time, so one solution does not serve another challenge") {
        ZIO.scoped:
          for
            v <- verifier()
            first <- v.issue(subject, score = 60).map(_.get)
            second <- v.issue(subject, score = 60).map(_.get)
            crossed <- v.verify(subject, second.token, solve(first))
          yield assertTrue(first.params("seed") != second.params("seed"), first.token != second.token, crossed == ChallengeResult.Failed)
      },
    ),
    suite("when switched off")(
      test("nothing is issued, so a Challenge decision stays a pause") {
        ZIO.scoped:
          for
            v <- verifier(settings.copy(enabled = false))
            issued <- v.issue(subject, score = 60)
          yield assertTrue(issued.isEmpty)
      },
    ),
  )
