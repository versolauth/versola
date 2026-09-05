package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ChallengeSettingsRecord, PasskeySettings, SubmissionLimits, TenantId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object ChallengeSettingsSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("ChallengeSettingsSyncClient")(
    test("fetches challenge settings from central") {
      val record = ChallengeSettingsRecord(
        tenantId = TenantId.default,
        allowedPrefixes = List("+1"),
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
      )
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
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
