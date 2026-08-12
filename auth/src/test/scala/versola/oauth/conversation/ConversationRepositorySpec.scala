package versola.oauth.conversation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.OtpCode
import versola.oauth.client.model.{AuthFlow, ClientId, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
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
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = None,
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
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = Some(email),
    userPhone = None,
    userLogin = None,
    userClaims = Some(zio.json.ast.Json.Obj()),
    authFlow = AuthFlow.default,
    userAgent = None,
    userAgentCookie = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
  )

  val record2 = record1.copy(
    userId = Some(userId2),
    credential = Some(Right(phone)),
    step = fakeOtp,
    authFlow = AuthFlow.default,
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
    step = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    userAgent = None,
    userAgentCookie = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
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
          notFound.isEmpty,
        )
      },
      test("delete conversation by auth ID") {
        for
          _ <- env.repository.create(authId1, record1, ttl)
          record <- env.repository.find(authId1).map(_.get)
          deleted <- env.repository.delete(authId1, record.version)
          found <- env.repository.find(authId1)
        yield assertTrue(deleted, found.isEmpty)
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
          overwritten <- env.repository.overwrite(authId1, updatedRecord.copy(version = found1.get.version))
          found2 <- env.repository.find(authId1)
        yield assertTrue(
          found1.contains(initial),
          overwritten,
          found2.contains(updatedRecord.copy(version = found1.get.version + 1)),
        )
      },
      test("overwrite with stale version returns false (optimistic conflict)") {
        for
          _ <- env.repository.create(authId1, record1, ttl)
          record <- env.repository.find(authId1).map(_.get)
          first <- env.repository.overwrite(authId1, record1.copy(step = fakeOtp, version = record.version))
          second <- env.repository.overwrite(authId1, record1.copy(step = fakeOtp, version = record.version))
        yield assertTrue(first, !second)
      },
      test("delete with stale version returns false (optimistic conflict)") {
        for
          _ <- env.repository.create(authId1, record1, ttl)
          record <- env.repository.find(authId1).map(_.get)
          first <- env.repository.delete(authId1, record.version)
          second <- env.repository.delete(authId1, record.version)
        yield assertTrue(first, !second)
      },
      test("overwrite preserves priorSessionId") {
        val mac = versola.util.MAC(Array.fill(32)(1.toByte))
        val initialWithPrior = initial.copy(
          priorSessionId = Some(mac)
        )
        val updatedRecord = initialWithPrior.copy(
          step = realOtp,
          userId = Some(userId1)
        )
        for
          _ <- env.repository.create(authId1, initialWithPrior, ttl)
          found1 <- env.repository.find(authId1).map(_.get)
          overwritten <- env.repository.overwrite(authId1, updatedRecord.copy(version = found1.version))
          found2 <- env.repository.find(authId1).map(_.get)
        yield assertTrue(
          found1.priorSessionId.exists(m => java.util.Arrays.equals(m: Array[Byte], mac: Array[Byte])),
          overwritten,
          found2.priorSessionId.exists(m => java.util.Arrays.equals(m: Array[Byte], mac: Array[Byte])),
          found2.version == found1.version + 1
        )
      },
      test("find returns None for expired conversation") {
        val pastAuthId = AuthId(UUID.fromString("00000000-0001-7000-8000-000000000001"))
        for
          _ <- env.repository.create(pastAuthId, record1, ttl = 1.second)
          _ <- TestClock.adjust(2.seconds)
          result <- env.repository.find(pastAuthId)
        yield assertTrue(result.isEmpty)
      },

    )

object ConversationRepositorySpec:
  case class Env(repository: ConversationRepository)
