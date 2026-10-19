package versola.central.configuration.challenges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.{RoleId, RoleRecord, RoleRepository}
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import versola.util.{JWT, Patch}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object OtpChallengeControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val tenantId = TenantId("tenant-a")
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val template =
    OtpTemplateRecord("default", tenantId, Map("en" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)

  private def settings(
      authConversationTtlSeconds: Int = 111,
      sessionTtlSeconds: Int = 222,
      sessionIdleTtlSeconds: Option[Int] = Some(333),
      userAgentTtlSeconds: Int = 444,
      acrVocabulary: Option[Map[String, List[String]]] = Some(Map("a" -> List("b"))),
      postLogoutRedirectUris: List[String] = List("https://existing.example/logout"),
      requireDpopNonce: Boolean = false,
      mtlsCertificateHeader: Option[String] = Some("ssl-client-cert"),
      mtlsCertificateEncoding: Option[MtlsCertificateEncoding] = Some(MtlsCertificateEncoding.urlEncodedPem),
      signingKeyId: Option[String] = None,
      clientAssertionMaxLifetimeSeconds: Int = 300,
      securityProfile: SecurityProfile = SecurityProfile.fapi2,
      logoLight: Option[String] = None,
      logoDark: Option[String] = None,
  ): ChallengeSettingsRecord =
    ChallengeSettingsRecord(
      tenantId = tenantId,
      phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
      emailRegistration = EmailRegistration.Closed,
      defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
      submissionLimits = SubmissionLimits.empty,
      otpLength = 6,
      otpResendAfter = 30,
      passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
      authConversationTtlSeconds = authConversationTtlSeconds,
      sessionTtlSeconds = sessionTtlSeconds,
      sessionIdleTtlSeconds = sessionIdleTtlSeconds,
      userAgentTtlSeconds = userAgentTtlSeconds,
      ipHeader = "X-Forwarded-For",
      acrVocabulary = acrVocabulary,
      postLogoutRedirectUris = postLogoutRedirectUris,
      requireDpopNonce = requireDpopNonce,
      mtlsCertificateHeader = mtlsCertificateHeader,
      mtlsCertificateEncoding = mtlsCertificateEncoding,
      signingKeyId = signingKeyId,
      clientAssertionMaxLifetimeSeconds = clientAssertionMaxLifetimeSeconds,
      securityProfile = securityProfile,
      logoLight = logoLight,
      logoDark = logoDark,
    )

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val userRole = RoleRecord(ChallengeSettingsRecord.DefaultRoleId, tenantId, Map("en" -> "User"), Set.empty, active = true)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[OtpChallengeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[OtpChallengeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
      settingsSetup: Stub[ChallengeSettingsService] => UIO[Unit] = _ => ZIO.unit,
      settingsVerify: (Response, Stub[ChallengeSettingsService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
      roleSetup: Stub[RoleRepository] => UIO[Unit] = _ => ZIO.unit,
      roleVerify: Stub[RoleRepository] => TestResult = _ => assertTrue(true),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[OtpChallengeService]
        challengeSettingsService = stub[ChallengeSettingsService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        roleRepository = stub[RoleRepository]
        _ = roleRepository.findRole.returnsWith(ZIO.succeed(Some(userRole)))
        _ = challengeSettingsService.getSecurityProfile.returnsWith(ZIO.succeed(SecurityProfile.fapi2))
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            OtpChallengeController.routes.provideEnvironment(
              ZEnvironment[OtpChallengeService](service) ++
                ZEnvironment[ChallengeSettingsService](challengeSettingsService) ++
                tracing ++ ZEnvironment[CentralConfig](config) ++ ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++ ZEnvironment[RoleRepository](roleRepository),
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        _ <- settingsSetup(challengeSettingsService)
        _ <- roleSetup(roleRepository)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
        settingsVerifyResult <- settingsVerify(response, challengeSettingsService)
      yield assertTrue(response.status == expectedStatus) && verifyResult && settingsVerifyResult && roleVerify(roleRepository)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def upsertWithProfile(
      profile: Option[SecurityProfile],
      regions: List[PhoneRegion] = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
      emailRegistration: EmailRegistration = EmailRegistration.Closed,
      defaultRoles: Set[RoleId] = Set(ChallengeSettingsRecord.DefaultRoleId),
      logoLight: Option[Patch[String]] = None,
      logoDark: Option[Patch[String]] = None,
  ): Request =
    Request(
      method = Method.PUT,
      url = URL.empty / "configuration" / "challenges" / "challenge-settings",
      body = Body.fromString(
        UpsertChallengeSettingsRequest(
          tenantId = tenantId,
          phoneRegions = regions,
          emailRegistration = emailRegistration,
          defaultRoles = defaultRoles,
          submissionLimits = SubmissionLimits.empty,
          otpLength = 6,
          otpResendAfter = 30,
          passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
          authConversationTtlSeconds = None,
          sessionTtlSeconds = None,
          sessionIdleTtlSeconds = None,
          userAgentTtlSeconds = None,
          ipHeader = "X-Forwarded-For",
          acrVocabulary = None,
          postLogoutRedirectUris = None,
          requireDpopNonce = None,
          mtlsCertificateHeader = None,
          mtlsCertificateEncoding = None,
          signingKeyId = None,
          clientAssertionMaxLifetimeSeconds = None,
          securityProfile = profile,
          logoLight = logoLight,
          logoDark = logoDark,
        ).toJson,
      ),
    ).addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("OtpChallengeController")(
    controllerTestCase(
      description = "PUT challenge-settings refuses a phone region whose regex does not compile",
      request = upsertWithProfile(None, List(PhoneRegion("KZ", "+7", 10, "[7", true))),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("phoneRegions regex of KZ"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings stores a logo pair",
      request = upsertWithProfile(
        None,
        logoLight = Some(Patch.Modified("https://cdn.example/light.svg")),
        logoDark = Some(Patch.Modified("https://cdn.example/dark.png")),
      ),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls.map(r => (r.logoLight, r.logoDark)) ==
            List((Some("https://cdn.example/light.svg"), Some("https://cdn.example/dark.png"))),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses a single logo file",
      request = upsertWithProfile(None, logoLight = Some(Patch.Modified("https://cdn.example/light.svg"))),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("set or cleared together"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses to clear one file of a stored pair",
      request = upsertWithProfile(None, logoLight = Some(Patch.Deleted)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(
          logoLight = Some("https://cdn.example/light.svg"),
          logoDark = Some("https://cdn.example/dark.svg"),
        ))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings clears both files of a stored pair, and keeps them when omitted",
      request = upsertWithProfile(None, logoLight = Some(Patch.Deleted), logoDark = Some(Patch.Deleted)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(
          logoLight = Some("https://cdn.example/light.svg"),
          logoDark = Some("https://cdn.example/dark.svg"),
        ))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(r => (r.logoLight, r.logoDark)) == List((None, None)))),
    ),
    controllerTestCase(
      description = "PUT challenge-settings keeps a stored logo pair when the request omits it",
      request = upsertWithProfile(None),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(
          logoLight = Some("https://cdn.example/light.svg"),
          logoDark = Some("https://cdn.example/dark.svg"),
        ))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls.map(r => (r.logoLight, r.logoDark)) ==
            List((Some("https://cdn.example/light.svg"), Some("https://cdn.example/dark.svg"))),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses a logo that is not an absolute HTTP(S) URL",
      request = upsertWithProfile(
        None,
        logoLight = Some(Patch.Modified("javascript:alert(1)")),
        logoDark = Some(Patch.Modified("https://cdn.example/dark.svg")),
      ),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("absolute HTTP(S) URLs"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings accepts regions that share a dial code",
      request = upsertWithProfile(None, List(PhoneRegion("KZ", "+7", 10, "7\\d{9}", true), PhoneRegion("RU", "+7", 10, "9\\d{9}", true))),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify =
        (_, service) => ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.phoneRegions.map(_.region)) == List(List("KZ", "RU")))),
    ),
    controllerTestCase(
      description = "PUT challenge-settings stores email domains lower-cased and in punycode",
      request = upsertWithProfile(
        None,
        emailRegistration = EmailRegistration(EmailRegistrationMode.allowlist, List(" Example.COM ", "*.Пример.рф"), false),
      ),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls.map(_.emailRegistration.domains) == List(List("example.com", "*.xn--e1afmkfd.xn--p1ai")),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses an email domain that is not a domain",
      request = upsertWithProfile(None, emailRegistration = EmailRegistration(EmailRegistrationMode.allowlist, List("not a domain"), false)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("emailRegistration domain 'not a domain'"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses empty default roles",
      request = upsertWithProfile(None, defaultRoles = Set.empty),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses a default role the tenant does not have while a credential can register",
      request = upsertWithProfile(None, emailRegistration = EmailRegistration(EmailRegistrationMode.open, Nil, false)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      roleSetup = roles => roles.findRole.succeedsWith(None),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("does not exist in tenant"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings does not look the default roles up while nobody can register",
      request = upsertWithProfile(None, regions = Nil),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      roleVerify = roles =>
        val lookups = roles.findRole.times
        assertTrue(lookups == 0),
    ),
    // A tenant's profile is fixed when the tenant is created.
    controllerTestCase(
      description = "PUT challenge-settings refuses a switch from fapi2 to standard",
      request = upsertWithProfile(Some(SecurityProfile.standard)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(securityProfile = SecurityProfile.fapi2))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      verify = (response, _) =>
        response.body.asString.map(body => assertTrue(body.contains("cannot be changed after the tenant is created"))),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses a switch from standard to fapi2",
      request = upsertWithProfile(Some(SecurityProfile.fapi2)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(securityProfile = SecurityProfile.standard))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.standard) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        val upserts = service.upsertSettings.times
        ZIO.succeed(assertTrue(upserts == 0)),
    ),
    // The settings read back from GET carry the profile, so a client that sends them back
    // unchanged has to be accepted.
    controllerTestCase(
      description = "PUT challenge-settings accepts the profile the tenant already has",
      request = upsertWithProfile(Some(SecurityProfile.standard)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(securityProfile = SecurityProfile.standard))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.standard) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.securityProfile) == List(SecurityProfile.standard))),
    ),
    // The profile written is the one in the repository, not the one in the cache `existing` came
    // from, which can lag a tenant created a moment ago.
    controllerTestCase(
      description = "PUT challenge-settings omitting securityProfile keeps the tenant's actual profile, not the cached one",
      request = upsertWithProfile(None),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(securityProfile = SecurityProfile.standard))) *>
          service.getSecurityProfile.succeedsWith(SecurityProfile.fapi2) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.securityProfile) == List(SecurityProfile.fapi2))),
    ),
    controllerTestCase(
      description = "GET otp-templates returns tenant templates",
      request = Request.get(
        (URL.empty / "configuration" / "challenges" / "otp-templates")
          .addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getTemplates.succeedsWith(Vector(template)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetOtpTemplatesResponse]
        yield assertTrue(
          service.getTemplates.calls == List(tenantId),
          payload == GetOtpTemplatesResponse(Vector(template)),
        ),
    ),
    controllerTestCase(
      description = "GET otp-templates/sync returns all templates for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "challenges" / "otp-templates" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getSyncTemplates.succeedsWith(Vector(template)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetOtpTemplatesResponse]
        yield assertTrue(
          service.getSyncTemplates.calls.length == 1,
          payload == GetOtpTemplatesResponse(Vector(template)),
        ),
    ),
    controllerTestCase(
      description = "reject otp-templates/sync request without service token",
      request = Request.get(URL.empty / "configuration" / "challenges" / "otp-templates" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getSyncTemplates.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT otp-templates upserts template and returns no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "otp-templates",
        body =
          Body.fromString(UpsertOtpTemplateRequest(template.id, template.tenantId, template.localizations, template.purpose, template.channel).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.upsertTemplate.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertTemplate.calls == List(template))),
    ),
    controllerTestCase(
      description = "DELETE otp-templates deletes template and returns no content",
      request = Request(
        method = Method.DELETE,
        url = URL.empty / "configuration" / "challenges" / "otp-templates",
        body = Body.fromString(DeleteOtpTemplateRequest(template.id, template.tenantId, template.purpose, OtpTemplateChannel.sms).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTemplate.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteTemplate.calls == List((template.id, template.tenantId, template.purpose, template.channel)))),
    ),
    controllerTestCase(
      description = "GET challenge-settings returns the tenant's settings",
      request = Request.get(
        (URL.empty / "configuration" / "challenges" / "challenge-settings")
          .addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      settingsSetup = service => service.getSettings.succeedsWith(Some(settings())),
      settingsVerify = (response, service) =>
        for payload <- response.body.asJson[GetChallengeSettingsResponse]
        yield assertTrue(
          service.getSettings.calls == List(tenantId),
          payload == GetChallengeSettingsResponse(Some(settings())),
        ),
    ),
    controllerTestCase(
      description = "GET challenge-settings/sync returns all settings for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "challenges" / "challenge-settings" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      settingsSetup = service => service.getAllSettings.succeedsWith(Vector(settings())),
      settingsVerify = (response, service) =>
        for payload <- response.body.asJson[GetAllChallengeSettingsResponse]
        yield assertTrue(
          service.getAllSettings.calls.length == 1,
          payload == GetAllChallengeSettingsResponse(Vector(settings())),
        ),
    ),
    controllerTestCase(
      description = "reject challenge-settings/sync request without service token",
      request = Request.get(URL.empty / "configuration" / "challenges" / "challenge-settings" / "sync"),
      expectedStatus = Status.Unauthorized,
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.getAllSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings prefers request-provided optional fields over existing settings",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = Some(10),
            sessionTtlSeconds = Some(20),
            sessionIdleTtlSeconds = Some(30),
            userAgentTtlSeconds = Some(40),
            ipHeader = "X-Forwarded-For",
            acrVocabulary = Some(Map("x" -> List("y"))),
            postLogoutRedirectUris = Some(List("https://new.example/logout")),
            requireDpopNonce = Some(true),
            mtlsCertificateHeader = Some(Patch.Modified("x-client-cert")),
            mtlsCertificateEncoding = Some(Patch.Modified(MtlsCertificateEncoding.base64Der)),
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(
            settings(
              authConversationTtlSeconds = 10,
              sessionTtlSeconds = 20,
              sessionIdleTtlSeconds = Some(30),
              userAgentTtlSeconds = 40,
              acrVocabulary = Some(Map("x" -> List("y"))),
              postLogoutRedirectUris = List("https://new.example/logout"),
              requireDpopNonce = true,
              mtlsCertificateHeader = Some("x-client-cert"),
              mtlsCertificateEncoding = Some(MtlsCertificateEncoding.base64Der),
            ),
          ),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings clears the certificate source when the console sends it null",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = Some(Patch.Deleted),
            mtlsCertificateEncoding = Some(Patch.Deleted),
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      // Turning a tenant's mutual TLS off is the one edit that has nothing to send but null,
      // so it is the edit an "omitted means keep" rule makes impossible.
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(
            settings(mtlsCertificateHeader = None, mtlsCertificateEncoding = None),
          ),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings selects the tenant's signing key",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = Some(Patch.Modified("ps-kid")),
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.signingKeyId) == List(Some("ps-kid")))),
    ),
    // Clearing it is the deliberate move back to auth's own configured key, and like every
    // other field here it is the one edit an "omitted means keep" rule cannot express.
    controllerTestCase(
      description = "PUT challenge-settings clears the signing key when the console sends it null",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = Some(Patch.Deleted),
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(signingKeyId = Some("ps-kid")))) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.signingKeyId) == List(None))),
    ),
    controllerTestCase(
      description = "PUT challenge-settings keeps the stored signing key when the field is omitted",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(signingKeyId = Some("ps-kid")))) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.map(_.signingKeyId) == List(Some("ps-kid")))),
    ),
    // The service is what knows which kids are signable; the controller's job is to report
    // its refusal as the operator's mistake rather than as a fault of this server.
    controllerTestCase(
      description = "PUT challenge-settings returns 400 naming the key when nothing can sign with it",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = Some(Patch.Modified("bootstrap-kid")),
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *>
          service.upsertSettings.failsWith(
            ChallengeSettingsService.ValidationError.VerifyOnlySigningKey("bootstrap-kid"),
          ),
      settingsVerify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.contains("bootstrap-kid")),
    ),
    controllerTestCase(
      description = "PUT challenge-settings stores a client assertion lifetime inside the accepted range",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = Some(120),
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(settings(clientAssertionMaxLifetimeSeconds = 120)),
        )),
    ),
    // A jti is only remembered for as long as the assertion carrying it can be used, and
    // `auth` sizes that memory on this ceiling -- a tenant past it would have replays it
    // can no longer detect.
    controllerTestCase(
      description = "PUT challenge-settings refuses a client assertion lifetime past the replay ring's reach",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds =
              Some(ChallengeSettingsRecord.MaxClientAssertionMaxLifetimeSeconds + 1),
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings refuses a header whose encoding was cleared",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = Some(Patch.Modified("x-client-cert")),
            mtlsCertificateEncoding = Some(Patch.Deleted),
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      // Each field resolves on its own, so nothing but this check stops an edit that leaves
      // the pair half-set -- which `auth` reads as no source at all rather than as an error.
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings falls back to existing settings when optional fields are omitted",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      // The stored settings carry the nonce requirement so the omission is load-bearing: an
      // absent field must not be the console quietly turning §8 back off for a tenant.
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings(requireDpopNonce = true))) *>
          service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls == List(settings(requireDpopNonce = true)))),
    ),
    controllerTestCase(
      description = "PUT challenge-settings falls back to defaults when there are no existing settings and fields are omitted",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
            emailRegistration = EmailRegistration.Closed,
            defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
            requireDpopNonce = None,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
            clientAssertionMaxLifetimeSeconds = None,
            securityProfile = None,
            logoLight = None,
            logoDark = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(
            settings(
              authConversationTtlSeconds = 900,
              sessionTtlSeconds = 86400,
              sessionIdleTtlSeconds = None,
              userAgentTtlSeconds = 15552000,
              acrVocabulary = None,
              postLogoutRedirectUris = Nil,
              mtlsCertificateHeader = None,
              mtlsCertificateEncoding = None,
            ),
          ),
        )),
    ),
  )
