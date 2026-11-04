package versola.oauth.conversation

import versola.auth.model.{CredentialDeviceType, CredentialId, OtpCode, PasskeyRecord}
import versola.oauth.authorize.model.{ResponseMode, ResponseTypeEntry}
import versola.oauth.client.model.{
  AuthFactor,
  AuthFactorType,
  AuthFlow,
  AuthMethodRef,
  ClientId,
  PassedAuthFactor,
  PassedFactorRecord,
  PasskeyAuthFlow,
  PasskeySettings,
  ScopeToken,
}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.user.model.UserId
import versola.util.{Email, Phone}
import zio.http.URL

import java.time.Instant
import java.util.UUID

/** What the specs of the alternatives to the code share: a user with a phone number and a passkey, a flow that lists
  * both the code and the passkey, and the conversation of that user on its code step. */
object CodeAlternativeFixtures:
  val authId = AuthId(UUID.fromString("018f2a4c-0000-7000-8000-0000000000aa"))
  val userId = UserId(UUID.fromString("018f2a4c-0000-7000-8000-0000000000bb"))
  val clientId = ClientId("test-client")
  val phone = Phone("+491512345678")
  val now = Instant.parse("2026-11-03T10:00:00Z")

  val passkeySettings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:3000"),
    userVerification = "preferred",
  )

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
    createdAt = now,
    updatedAt = now,
  )

  /** Phone and code; the flow can ask for a passkey, which nothing says counts as the code. */
  val flowWithPasskey: AuthFlow = AuthFlow.default.copy(passkey = Some(PasskeyAuthFlow(factors = Nil)))

  /** The same, and a passkey counts as the code (`equivalents` is keyed by the factor that was passed). */
  val flowWherePasskeyIsTheCode: AuthFlow =
    flowWithPasskey.copy(equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp)))

  /** A code that was sent. */
  val sentCode = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(OtpCode("123456"))),
    timesRequested = 1,
    timesSubmitted = 0,
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = Some(now),
  )

  /** A code step where nothing was sent: a fake send, or a refused one answered as a send. */
  val fakeCode = sentCode.copy(real = None)

  /** A signed-in user's conversation on its code step. */
  val onCode = ConversationRecord(
    clientId = clientId,
    redirectUri = URL.decode("https://example.com/callback").toOption.get,
    scope = Set(ScopeToken("openid")),
    codeChallenge = Some(CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")),
    codeChallengeMethod = Some(CodeChallengeMethod.S256),
    state = None,
    userId = Some(userId),
    credential = Some(Right(phone)),
    step = sentCode,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(ResponseTypeEntry.Code),
    responseMode = ResponseMode.Query,
    userEmail = None,
    userPhone = Some(phone),
    userLogin = None,
    userClaims = None,
    authFlow = flowWithPasskey,
    registrationFlow = None,
    registrationStep = None,
    userAgent = None,
    userAgentCookie = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
    authorizationDetails = None,
    grantedScope = None,
    promptConsent = false,
    dpopJkt = None,
  )

  /** The code step of a person who has asked for the options of a passkey from the code page. */
  def asking(code: ConversationStep.Otp, request: String = "req-state"): ConversationStep.Otp = code.copy(passkeyRequest = Some(request))

  def passedPasskey(at: Instant = now): (PassedAuthFactor, PassedFactorRecord) =
    PassedAuthFactor.passkey -> PassedFactorRecord(at, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa))
