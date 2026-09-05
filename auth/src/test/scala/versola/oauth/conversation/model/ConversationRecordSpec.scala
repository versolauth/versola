package versola.oauth.conversation.model

import versola.auth.model.OtpCode
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.client.model.{AuthFlow, ClientId, PrimaryCredential, RegistrationCredential, RegistrationFlow, RegistrationStep, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, State}
import versola.user.model.{Login, UserId}
import versola.util.{Email, Phone}
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object ConversationRecordSpec extends ZIOSpecDefault:

  private val userId = UserId(UUID.fromString("00000000-0000-7000-8000-000000000001"))
  private val email = Email("test@example.com")
  private val phone = Phone("+12025551234")
  private val redirectUri = zio.http.URL.decode("https://example.com/callback").toOption.get

  private val credentialStep = ConversationStep.Credential(List(PrimaryCredential.email), true, false)

  private val record = ConversationRecord(
    clientId = ClientId("test-client"),
    redirectUri = redirectUri,
    scope = Set(ScopeToken("read"), ScopeToken("write")),
    codeChallenge = CodeChallenge("a" * 43),
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = Some(State("test-state")),
    userId = None,
    credential = None,
    step = credentialStep,
    requestedClaims = None,
    uiLocales = Some(List("en")),
    nonce = None,
    responseType = zio.prelude.NonEmptySet(ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    registrationFlow = None,
    registrationStep = None,
    userAgent = None,
    userAgentCookie = None,
    version = 1,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
    authorizationDetails = None,
    grantedScope = None,
    promptConsent = false,
  )

  private val registrationFlow = RegistrationFlow(
    credential = RegistrationCredential.email,
    steps = List(RegistrationStep.Otp(), RegistrationStep.SetPassword()),
    roleIds = Set.empty,
  )

  def spec = suite("ConversationRecord")(
    suite("user")(
      test("is absent until the conversation is bound to a user") {
        assertTrue(record.user.isEmpty)
      },
      test("projects the identity fields carried on the conversation") {
        val identified = record.copy(
          userId = Some(userId),
          userEmail = Some(email),
          userPhone = Some(phone),
          userLogin = Some(Login("testuser")),
          userClaims = Some(Json.Obj("name" -> Json.Str("Test User"))),
        )
        val user = identified.user
        assertTrue(
          user.map(_.id) == Some(userId),
          user.flatMap(_.email) == Some(email),
          user.flatMap(_.phone) == Some(phone),
          user.flatMap(_.login) == Some(Login("testuser")),
          user.map(_.claims) == Some(Json.Obj("name" -> Json.Str("Test User"))),
          user.flatMap(_.uiLocales) == Some(List("en")),
        )
      },
      test("falls back to empty claims when the conversation carries none") {
        assertTrue(record.copy(userId = Some(userId)).user.map(_.claims) == Some(Json.Obj.empty))
      },
    ),
    suite("effectiveScope")(
      test("is the requested scope until consent has been decided") {
        assertTrue(record.effectiveScope == Set(ScopeToken("read"), ScopeToken("write")))
      },
      test("is the granted subset once consent has been decided") {
        val granted = record.copy(grantedScope = Some(Set(ScopeToken("read"))))
        assertTrue(granted.effectiveScope == Set(ScopeToken("read")))
      },
      test("an empty grant supersedes the requested scope rather than falling back to it") {
        assertTrue(record.copy(grantedScope = Some(Set.empty)).effectiveScope.isEmpty)
      },
    ),
    suite("hasOfflineAccess")(
      test("is false when offline_access was not requested") {
        assertTrue(!record.hasOfflineAccess)
      },
      test("is true when offline_access is in the requested scope") {
        assertTrue(record.copy(scope = Set(ScopeToken.OfflineAccess)).hasOfflineAccess)
      },
      test("follows the granted scope, so a dropped offline_access disables refresh") {
        val granted = record.copy(
          scope = Set(ScopeToken.OfflineAccess),
          grantedScope = Some(Set(ScopeToken("read"))),
        )
        assertTrue(!granted.hasOfflineAccess)
      },
    ),
    suite("registration")(
      test("a conversation without a registration step is a sign-in") {
        assertTrue(!record.isRegistering, record.currentRegistrationStep.isEmpty)
      },
      test("isRegistering follows the presence of the step index") {
        assertTrue(record.copy(registrationStep = Some(0)).isRegistering)
      },
      test("currentRegistrationStep reads the indexed step out of the flow") {
        val registering = record.copy(
          registrationFlow = Some(registrationFlow),
          registrationStep = Some(1),
        )
        assertTrue(registering.currentRegistrationStep == Some(RegistrationStep.SetPassword()))
      },
      test("currentRegistrationStep is empty once the flow is exhausted") {
        val exhausted = record.copy(
          registrationFlow = Some(registrationFlow),
          registrationStep = Some(2),
        )
        assertTrue(exhausted.currentRegistrationStep.isEmpty)
      },
      test("currentRegistrationStep is empty when the client has no registration flow") {
        assertTrue(record.copy(registrationStep = Some(0)).currentRegistrationStep.isEmpty)
      },
    ),
    suite("patch")(
      test("the empty patch leaves the record untouched") {
        assertTrue(record.patch(ConversationRecord.Patch.empty) == record)
      },
      test("reports the empty patch as empty") {
        assertTrue(
          ConversationRecord.Patch.empty.isEmpty,
          !ConversationRecord.Patch.empty.copy(step = Some(credentialStep)).isEmpty,
        )
      },
      test("applies each supplied field and keeps the rest") {
        val step = ConversationStep.SetPassword(0, 0, false, false)
        val patched = record.patch(
          ConversationRecord.Patch(
            userId = Some(Some(userId)),
            credential = Some(Some(Left(email))),
            step = Some(step),
            authFlow = None,
          )
        )
        assertTrue(
          patched.userId == Some(userId),
          patched.credential == Some(Left(email)),
          patched.step == step,
          patched.authFlow == record.authFlow,
          patched.csrfToken == record.csrfToken,
        )
      },
      test("a patch can clear the user and credential rather than only set them") {
        val bound = record.copy(userId = Some(userId), credential = Some(Left(email)))
        val cleared = bound.patch(
          ConversationRecord.Patch(
            userId = Some(None),
            credential = Some(None),
            step = None,
            authFlow = None,
          )
        )
        assertTrue(cleared.userId.isEmpty, cleared.credential.isEmpty)
      },
    ),
    suite("step extractors")(
      test("Otp matches only when a credential is bound alongside the step") {
        val otpStep = ConversationStep.Otp(Some(ConversationStep.Otp.Real(OtpCode("123456"))), 1, 0, 0, false, 0, None)
        val withCredential = record.copy(step = otpStep, credential = Some(Right(phone)))
        val withoutCredential = record.copy(step = otpStep)
        assertTrue(
          ConversationRecord.Otp.unapply(withCredential) == Some((otpStep, Right(phone))),
          ConversationRecord.Otp.unapply(withoutCredential).isEmpty,
          ConversationRecord.Otp.unapply(record).isEmpty,
        )
      },
      test("Password matches the password step") {
        val step = ConversationStep.Password(0, None, 0, false)
        assertTrue(
          ConversationRecord.Password.unapply(record.copy(step = step)) == Some(step),
          ConversationRecord.Password.unapply(record).isEmpty,
        )
      },
      test("SetPassword matches the set-password step") {
        val step = ConversationStep.SetPassword(0, 0, false, false)
        assertTrue(
          ConversationRecord.SetPassword.unapply(record.copy(step = step)) == Some(step),
          ConversationRecord.SetPassword.unapply(record).isEmpty,
        )
      },
      test("PasskeyEnroll matches the enrollment step") {
        val step = ConversationStep.PasskeyEnroll("request", "options")
        assertTrue(
          ConversationRecord.PasskeyEnroll.unapply(record.copy(step = step)) == Some(step),
          ConversationRecord.PasskeyEnroll.unapply(record).isEmpty,
        )
      },
      test("Consent matches the consent step") {
        val step = ConversationStep.Consent(Set(ScopeToken("read")), allowPartial = true)
        assertTrue(
          ConversationRecord.Consent.unapply(record.copy(step = step)) == Some(step),
          ConversationRecord.Consent.unapply(record).isEmpty,
        )
      },
    ),
  )
