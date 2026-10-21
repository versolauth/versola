package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.auth.model.{CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFactor, AuthFactorType, AuthFlow, ClientId, PassedAuthFactor, PasskeyAuthFlow}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.UnitSpecBase
import zio.ZIO
import zio.prelude.NonEmptyList
import zio.test.*

import java.time.Instant
import java.util.UUID

object AcrResolutionServiceSpec extends UnitSpecBase:

  case class Env(
      configurationService: Stub[OAuthConfigurationService],
      userRepository: Stub[UserRepository],
      passkeyRepository: Stub[PasskeyRepository],
      passwordService: Stub[PasswordService],
      service: AcrResolutionService,
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
      passwordService,
    )
    Env(configurationService, userRepository, passkeyRepository, passwordService, service)

  val clientId = ClientId("test-client")
  val userId = UserId(UUID.randomUUID())
  val flow = AuthFlow.default
  val passkeyFlow = flow.copy(passkey = Some(PasskeyAuthFlow(Nil)))
  val passwordFlow = flow.copy(primary = flow.primary.copy(factors = List(AuthFactor(AuthFactorType.password, required = true))))
  val passwordAndOtpFlow = flow.copy(primary =
    flow.primary.copy(factors =
      List(
        AuthFactor(AuthFactorType.password, required = true),
        AuthFactor(AuthFactorType.otp, required = false),
      ),
    ),
  )
  val someone = UserRecord.empty(userId).copy(email = Some(versola.util.Email("test@example.com")))
  val aPasskey = PasskeyRecord(
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
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passkeyAcr), clientId, passkeyFlow, Set.empty)
        yield assertTrue(result == Some(passkeyAcr))
      },
      test("return None for a passkey ACR when the flow offers no passkey, however many the user has") {
        val env = makeEnv
        val passkeyAcr = Acr("passkey_acr")
        val vocabulary = Map(passkeyAcr -> NonEmptyList(PassedAuthFactor.passkey))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passkeyAcr), clientId, flow, Set.empty)
          listCalls = env.passkeyRepository.listByUser.times
        yield assertTrue(result.isEmpty, listCalls == 0)
      },
      test("short-circuit: return first matching ACR and don't evaluate the second") {
        val env = makeEnv
        val vocabulary = Map(
          passwordAcr -> NonEmptyList(PassedAuthFactor.password),
          mfaAcr -> NonEmptyList(PassedAuthFactor.otp),
        )
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passwordService.hasPassword.succeedsWith(true)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(passwordAcr, mfaAcr), clientId, flow, Set.empty)
        yield assertTrue(
          result == Some(passwordAcr),
          env.userRepository.find.calls.isEmpty, // otp check was skipped
        )
      },
      test("fall-through: first ACR not achievable, second is") {
        val env = makeEnv
        val vocabulary = Map(
          passwordAcr -> NonEmptyList(PassedAuthFactor.password),
          mfaAcr -> NonEmptyList(PassedAuthFactor.otp),
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
          acr2 -> NonEmptyList(PassedAuthFactor.otp),
        )
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          _ <- env.passwordService.hasPassword.succeedsWith(false) // acr1 fails at password
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId).copy(email =
            Some(versola.util.Email("test@example.com")),
          ))) // otp check for acr2 (or acr1 if it didn't fail earlier)
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(acr1, acr2), clientId, flow, Set.empty)
        yield assertTrue(
          result == Some(acr2),
          env.userRepository.find.calls.size == 1,
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
          env.userRepository.find.calls.isEmpty, // No DB call because session satisfies it
        )
      },
    ),
    suite("checkAcrSatisfaction")(
      test("return true and matching ACR when satisfied") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, userId, flow)
        yield assertTrue(result == Some(mfaAcr))
      },
      test("return None when not satisfied") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp, PassedAuthFactor.password))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, userId, flow)
        yield assertTrue(result.isEmpty)
      },
      test("satisfied via equivalents") {
        val env = makeEnv
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.passkey)
        val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr), amr, userId, flow.copy(equivalents = equivalents))
        yield assertTrue(result == Some(mfaAcr))
      },
      test("return None when ACR is not in the vocabulary") {
        val env = makeEnv
        val unknownAcr = Acr("unknown")
        val vocabulary = Map(mfaAcr -> NonEmptyList(PassedAuthFactor.otp))
        val amr = Set(PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(unknownAcr), amr, userId, flow)
        yield assertTrue(result.isEmpty)
      },
      test("fall through to second ACR when first is not satisfied") {
        val env = makeEnv
        val otpAcr = Acr("otp")
        val vocabulary = Map(
          mfaAcr -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp),
          otpAcr -> NonEmptyList(PassedAuthFactor.otp),
        )
        val amr = Set(PassedAuthFactor.otp) // satisfies otpAcr but not mfaAcr
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(mfaAcr, otpAcr), amr, userId, flow)
        yield assertTrue(result == Some(otpAcr))
      },
    ),
    suite("the built-in sensitive class")(
      test("asks a user who has a passkey for the passkey") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, passkeyFlow)
        yield assertTrue(result == Some(NonEmptyList(PassedAuthFactor.passkey)))
      },
      test("never lets a one-time code stand in for a passkey the flow cannot ask for") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, flow)
        yield assertTrue(result.isEmpty, env.userRepository.find.calls.isEmpty)
      },
      test("asks a user with a password but no passkey for the password, not a one-time code") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(true)
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, passwordAndOtpFlow)
        yield assertTrue(result == Some(NonEmptyList(PassedAuthFactor.password)))
      },
      test("asks for a one-time code when the user has no passkey and no password") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(someone))
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, flow)
        yield assertTrue(result == Some(NonEmptyList(PassedAuthFactor.otp)))
      },
      test("falls back to a one-time code when the flow cannot ask for the password the user has") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(true)
          _ <- env.userRepository.find.succeedsWith(Some(someone))
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, flow)
        yield assertTrue(result == Some(NonEmptyList(PassedAuthFactor.otp)))
      },
      test("asks for the password when the flow takes it on the credential card") {
        val env = makeEnv
        val inlineFlow = flow.copy(primary = flow.primary.copy(inlinePassword = true, factors = Nil))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(true)
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, inlineFlow)
        yield assertTrue(result == Some(NonEmptyList(PassedAuthFactor.password)))
      },
      test("has nothing to ask a user who has no factor the flow offers") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId)))
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, flow)
        yield assertTrue(result.isEmpty)
      },
      test("gives way to a definition of the same name in the tenant's vocabulary") {
        val env = makeEnv
        val vocabulary = Map(AcrResolutionService.Sensitive -> NonEmptyList(PassedAuthFactor.otp))
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(vocabulary)
          result <- env.service.builtInFactors(clientId, AcrResolutionService.Sensitive, userId, passkeyFlow)
        yield assertTrue(
          result.isEmpty,
          env.passkeyRepository.listByUser.calls.isEmpty,
        )
      },
      test("does not look anything up for a name that is not built in") {
        val env = makeEnv
        for
          result <- env.service.builtInFactors(clientId, mfaAcr, userId, flow)
        yield assertTrue(
          result.isEmpty,
          env.configurationService.getAcrVocabulary.calls.isEmpty,
        )
      },
      test("is achievable for a user who has a factor the flow offers, without being in the vocabulary") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(someone))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(AcrResolutionService.Sensitive), clientId, flow, Set.empty)
        yield assertTrue(result == Some(AcrResolutionService.Sensitive))
      },
      test("is not achievable for a user with a passkey the flow cannot ask for") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          result <- env.service.resolveAchievableAcr(userId, NonEmptyList(AcrResolutionService.Sensitive), clientId, flow, Set.empty)
        yield assertTrue(result.isEmpty)
      },
      test("is satisfied by a session that already passed the factor this user is asked for") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(someone))
          result <- env.service.checkAcrSatisfaction(
            clientId,
            NonEmptyList(AcrResolutionService.Sensitive),
            Set(PassedAuthFactor.otp),
            userId,
            flow,
          )
        yield assertTrue(result == Some(AcrResolutionService.Sensitive))
      },
      test("is satisfied by a session that passed the passkey of a user who has one") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          result <- env.service.checkAcrSatisfaction(
            clientId,
            NonEmptyList(AcrResolutionService.Sensitive),
            Set(PassedAuthFactor.passkey),
            userId,
            passkeyFlow,
          )
        yield assertTrue(result == Some(AcrResolutionService.Sensitive))
      },
      test("is not satisfied by a session that passed only a password or a one-time code, for a user who has a passkey") {
        val env = makeEnv
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          result <- env.service.checkAcrSatisfaction(
            clientId,
            NonEmptyList(AcrResolutionService.Sensitive),
            Set(PassedAuthFactor.otp, PassedAuthFactor.password),
            userId,
            passkeyFlow,
          )
        yield assertTrue(result.isEmpty)
      },
      test("is not satisfied when nothing the flow offers can satisfy it for the user, whatever the session passed") {
        val env = makeEnv
        val amr = Set(PassedAuthFactor.passkey, PassedAuthFactor.password, PassedAuthFactor.otp)
        for
          _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.passwordService.hasPassword.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId)))
          result <- env.service.checkAcrSatisfaction(clientId, NonEmptyList(AcrResolutionService.Sensitive), amr, userId, flow)
        yield assertTrue(result.isEmpty)
      },
    ),
  )
