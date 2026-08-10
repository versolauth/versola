package versola.oauth.authorize

import versola.auth.model.{CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, ClientId, PassedAuthFactor}
import versola.user.UserRepository
import versola.user.model.{UserRecord, UserId}
import versola.util.UnitSpecBase
import zio.prelude.NonEmptyList
import zio.test.*
import zio.ZIO
import org.scalamock.stubs.Stub
import java.time.Instant
import java.util.UUID

object AcrResolutionServiceSpec extends UnitSpecBase:

  case class Env(
      configurationService: Stub[OAuthConfigurationService],
      userRepository: Stub[UserRepository],
      passkeyRepository: Stub[PasskeyRepository],
      passwordService: Stub[PasswordService],
      service: AcrResolutionService
  )

  def makeEnv =
    val configurationService = stub[OAuthConfigurationService]
    val userRepository = stub[UserRepository]
    val passkeyRepository = stub[PasskeyRepository]
    val passwordService = stub[PasswordService]
    val service = AcrResolutionService.Impl(
      configurationService,
      userRepository,
      passkeyRepository,
      passwordService
    )
    Env(configurationService, userRepository, passkeyRepository, passwordService, service)

  val clientId = ClientId("test-client")
  val userId = UserId(UUID.randomUUID())
  val flow = AuthFlow.default
  val mfaAcr = Acr("mfa")
  val passwordAcr = Acr("password")

  def spec = suite("AcrResolutionService")(
    suite("resolveAchievableAcr")(
      test("return None when vocabulary is empty") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(result.isEmpty)
      },
      test("return matching ACR when user has the required factor (OTP via userRepository)") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val user = UserRecord.empty(userId).copy(email = Some(versola.util.Email("test@example.com")))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(result == Some(mfaAcr))
      },
      test("return None when required factor is NOT achievable") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val user = UserRecord.empty(userId) // no email, no phone
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(result.isEmpty)
      },
      test("return matching ACR when user has a registered passkey") {
        val env = makeEnv
        val passkeyAcr = Acr("passkey_acr")
        val vocabulary = Map(passkeyAcr -> NonEmptyList(PassedAuthFactor.passkey))
        val dummyPasskey = PasskeyRecord(
          id = CredentialId(Array.fill(32)(1.toByte)),
          userId = userId,
          publicKey = Array.fill(16)(7.toByte),
          signatureCounter = 0L,
          deviceType = CredentialDeviceType.MultiDevice,
          backedUp = true,
          backupEligible = true,
          transports = List.empty,
          attestationObject = None,
          clientDataJson = None,
          aaguid = None,
          name = None,
          lastUsedAt = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(dummyPasskey))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passkeyAcr), clientId, flow, Set.empty)
        yield assertTrue(result == Some(passkeyAcr))
      },
      test("short-circuit: return first matching ACR and don't evaluate the second") {
        val env = makeEnv
        val vocabulary = Map(
          passwordAcr -> NonEmptyList(PassedAuthFactor.password),
          mfaAcr -> NonEmptyList(PassedAuthFactor.otp)
        )
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passwordService.hasPassword.succeedsWith(true)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passwordAcr, mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(
          result == Some(passwordAcr),
          env.userRepository.find.calls.isEmpty // otp check was skipped
        )
      },
      test("fall-through: first ACR not achievable, second is") {
        val env = makeEnv
        val vocabulary = Map(
          passwordAcr -> NonEmptyList(PassedAuthFactor.password),
          mfaAcr -> NonEmptyList(PassedAuthFactor.otp)
        )
        val user = UserRecord.empty(userId).copy(email = Some(versola.util.Email("test@example.com")))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passwordAcr, mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(result == Some(mfaAcr))
      },
      test("memoization: DB called only once for same factor across multiple ACRs") {
        val env = makeEnv
        val acr1 = Acr("acr1")
        val acr2 = Acr("acr2")
        val vocabulary = Map(
          acr1 -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp),
          acr2 -> NonEmptyList(PassedAuthFactor.otp)
        )
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passwordService.hasPassword.succeedsWith(false) // acr1 fails at password
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId).copy(email = Some(versola.util.Email("test@example.com"))))) // otp check for acr2 (or acr1 if it didn't fail earlier)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(acr1, acr2), clientId, flow, Set.empty)
        yield assertTrue(
          result == Some(acr2),
          env.userRepository.find.calls.size == 1
        )
      },
      test("satisfied by sessionAmr via equivalents (no DB call)") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        // passkey satisfies otp
        val flowWithEquivalents = flow.copy(equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp)))
        val sessionAmr = Set(PassedAuthFactor.passkey)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(mfaAcr), clientId, flowWithEquivalents, sessionAmr)
        yield assertTrue(
          result == Some(mfaAcr),
          env.userRepository.find.calls.isEmpty // No DB call because session satisfies it
        )
      }
    ),
    suite("checkAcrSatisfaction")(
      test("return true and matching ACR when satisfied") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, Map.empty)
        yield assertTrue(result == Some(mfaAcr))
      },
      test("return None when not satisfied") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp, PassedAuthFactor.password))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, Map.empty)
        yield assertTrue(result.isEmpty)
      },
      test("satisfied via equivalents") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.passkey)
        val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, equivalents)
        yield assertTrue(result == Some(mfaAcr))
      },
      test("return None when ACR is not in the vocabulary") {
        val env = makeEnv
        val unknownAcr = Acr("unknown")
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(unknownAcr), amr, Map.empty)
        yield assertTrue(result.isEmpty)
      },
      test("fall through to second ACR when first is not satisfied") {
        val env = makeEnv
        val otpAcr = Acr("otp")
        val vocabulary = Map(
          mfaAcr  -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp),
          otpAcr  -> NonEmptyList(PassedAuthFactor.otp),
        )
        val amr = Set(PassedAuthFactor.otp) // satisfies otpAcr but not mfaAcr
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr, otpAcr), amr, Map.empty)
        yield assertTrue(result == Some(otpAcr))
      },
    )
  )
