package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.EmailRegistration
import versola.oauth.client.model.{AuditSettings, ChallengeSettingsRecord, PasskeySettings, PhoneRegion, SecurityProfile, SubmissionLimits, TenantId}
import versola.role.model.RoleId
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ChallengeSettingsSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private val record = ChallengeSettingsRecord(
    tenantId = TenantId.default,
    phoneRegions = List(PhoneRegion("US", "+1", 10, "\\d{10}", true)),
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(RoleId("user")),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 30,
    passkeySettings = PasskeySettings("idp.example", "Versola", List("https://idp.example"), "preferred"),
    authConversationTtlSeconds = 600,
    sessionTtlSeconds = 3600,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = 3600,
    ipHeader = "X-Forwarded-For",
    acrVocabulary = None,
    postLogoutRedirectUris = List.empty,
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = None,
    logoDark = None,
    // not the defaults, so that a switch the record lost on the way would show
    // not the defaults, so that a switch the record lost on the way would show
    auditSettings = Some(AuditSettings(log = false, tokenRefresh = true)),
  )

  def spec = suite("ChallengeSettingsSyncClient")(
    test("fetches challenge settings from central") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(
              Response.json(ChallengeSettingsSyncClient.ChallengeSettingsResponse(Vector(record)).toJson),
            )
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = ChallengeSettingsSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        settings <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/challenges/challenge-settings/sync"),
        settings == Vector(record),
      )
    },
    test("decodes the response of a central that does not send the audit switches, as the log on and the refreshes off") {
      // what the previous release of central serves: the record without `auditSettings`
      val older =
        record.toJsonAST.toOption.flatMap(_.asObject).map(obj => obj.copy(fields = obj.fields.filterNot((name, _) => name == "auditSettings")))
      val body = older.map(obj => Json.Obj("settings" -> Json.Arr(obj)).toJson)
      for
        _ <- TestClient.addRoutes(Handler.fromFunction[Request](_ => Response.json(body.getOrElse(""))).toRoutes)
        client <- ZIO.service[Client]
        settings <- ChallengeSettingsSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)).getAll
      yield assertTrue(
        older.exists(!_.fields.exists(_._1 == "auditSettings")),
        settings == Vector(record.copy(auditSettings = None)),
        settings.flatMap(_.auditSettings).isEmpty,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
