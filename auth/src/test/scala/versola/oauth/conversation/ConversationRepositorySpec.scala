package versola.oauth.conversation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.OtpCode
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, State}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, Email, Phone}
import zio.*
import zio.http.URL
import zio.test.*

import java.util.UUID

trait ConversationRepositorySpec extends DatabaseSpecBase[ConversationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val authId2 = AuthId(UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"))
  val authId3 = AuthId(UUID.fromString("cccccccc-cccc-dddd-eeee-ffffffffffff"))

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  val email = Email("test@example.com")
  val phone = Phone("+1234567890")
  val otpCode = OtpCode("123456")
  val ttl = 15.minutes

  val realOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 1,
    timesSubmitted = 0,
  )

  val fakeOtp = realOtp.copy(real = None)

  val record1 = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = Some(State("test-state")),
    userId = Some(userId1),
    credential = Some(Left(email)),
    step = realOtp,
  )

  val record2 = record1.copy(
    userId = Some(userId2),
    credential = Some(Right(phone)),
    step = fakeOtp,
  )

  val initial = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = None,
    userId = None,
    credential = None,
    step = ConversationStep.Empty(PrimaryCredential.Phone, passkey = false),
  )

  def testCases(env: ConversationRepositorySpec.Env): List[Spec[ConversationRepositorySpec.Env & zio.Scope, Any]] =
    List(
      test("create and find conversation records") {
        for
          _ <- env.repository.create(authId1, record1, ttl)
          _ <- env.repository.create(authId2, record2, ttl)
          found1 <- env.repository.find(authId1)
          found2 <- env.repository.find(authId2)
          notFound <- env.repository.find(authId3)

        yield assertTrue(
          found1.contains(record1),
          found2.contains(record2),
          notFound.isEmpty
        )
      },
      test("delete conversation by auth ID") {
        for
          _ <- env.repository.create(authId1, record1, ttl)
          _ <- env.repository.delete(authId1)
          found <- env.repository.find(authId1)
        yield assertTrue(found.isEmpty)
      },
      test("overwrite conversation record") {
        val updatedRecord = initial.copy(
          step = realOtp,
          userId = Some(userId1),
          credential = Some(Left(email)),
        )
        for
          _ <- env.repository.create(authId1, initial, ttl)
          found1 <- env.repository.find(authId1)
          _ <- env.repository.overwrite(authId1, updatedRecord)
          found2 <- env.repository.find(authId1)
        yield assertTrue(
          found1.contains(initial),
          found2.contains(updatedRecord)
        )
      },
    )

object ConversationRepositorySpec:
  case class Env(repository: ConversationRepository)
