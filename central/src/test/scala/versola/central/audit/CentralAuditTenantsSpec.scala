package versola.central.audit

import versola.central.configuration.challenges.*
import versola.central.configuration.tenants.TenantId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object CentralAuditTenantsSpec extends UnitSpecBase:

  private val acme = TenantId("acme")

  private def settings(auditLog: Boolean) = ChallengeSettingsRecord(
    tenantId = acme,
    phoneRegions = Nil,
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("localhost", "Test", List("http://localhost"), "preferred"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
    postLogoutRedirectUris = Nil,
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = None,
    logoDark = None,
    auditSettings = AuditSettings(log = auditLog, tokenRefresh = false),
  )

  def spec = suite("CentralAuditTenants")(
    test("a tenant that has switched its log off does not record") {
      val service = stub[ChallengeSettingsService]
      for
        _ <- service.getSettings.succeedsWith(Some(settings(auditLog = false)))
        recording <- CentralAuditTenants.Impl(service).recording("acme")
      yield assertTrue(!recording, service.getSettings.calls == List(acme))
    },
    test("a tenant whose log is on records") {
      val service = stub[ChallengeSettingsService]
      for
        _ <- service.getSettings.succeedsWith(Some(settings(auditLog = true)))
        recording <- CentralAuditTenants.Impl(service).recording("acme")
      yield assertTrue(recording)
    },
    test("a tenant with no settings keeps a log") {
      val service = stub[ChallengeSettingsService]
      for
        _ <- service.getSettings.succeedsWith(None)
        recording <- CentralAuditTenants.Impl(service).recording("acme")
      yield assertTrue(recording)
    },
    test("a switch that cannot be read keeps the log: the event is recorded") {
      val service = stub[ChallengeSettingsService]
      for
        _ <- service.getSettings.failsWith(RuntimeException("database down"))
        recording <- CentralAuditTenants.Impl(service).recording("acme")
      yield assertTrue(recording)
    },
  ) @@ TestAspect.silentLogging
