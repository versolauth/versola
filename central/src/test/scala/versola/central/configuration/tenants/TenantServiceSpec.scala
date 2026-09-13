package versola.central.configuration.tenants

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, PasskeySettings, SubmissionLimits}
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object TenantServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenant1 = TenantId("tenant-a")
  private val tenant2 = TenantId("tenant-b")

  private val tenantRecord1 = TenantRecord(tenant1, "Tenant A", None)
  private val tenantRecord2 = TenantRecord(tenant2, "Tenant B", None)

  private val createRequest = CreateTenantRequest(tenant1, "Tenant A", None)
  private val updateRequest = UpdateTenantRequest(tenant1, "Updated Tenant A", None)

  class Env(initial: Vector[TenantRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[TenantRepository]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val service = TenantService.Impl(cache, repository, challengeSettingsService, TestCentralConfig.config)

  def spec = suite("TenantService")(
    test("getAllTenants returns cached tenants sorted by id") {
      val env = new Env(Vector(tenantRecord2, tenantRecord1))

      for
        result <- env.service.getAllTenants
      yield assertTrue(result == Vector(tenantRecord1, tenantRecord2))
    },
    test("createTenant delegates request fields to repository and seeds challenge settings") {
      val env = new Env()

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.challengeSettingsService.upsertSettings.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(
        env.repository.createTenant.calls == List((tenant1, "Tenant A", None)),
        env.challengeSettingsService.upsertSettings.calls ==
          List(ChallengeSettingsRecord(
            tenantId = tenant1,
            allowedPrefixes = Nil,
            submissionLimits = SubmissionLimits.recommended,
            otpLength = 6,
            otpResendAfter = 60,
            passkeySettings = PasskeySettings("", "Versola", Nil, "preferred"),
            authConversationTtlSeconds = 900,
            sessionTtlSeconds = 86400,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = 15552000,
            ipHeader = "X-Real-IP",
            acrVocabulary = None,
            postLogoutRedirectUris = Nil,
          )),
      )
    },
    test("createTenant always seeds the recommended submission limits -- the request has no override for them") {
      val env = new Env()

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.challengeSettingsService.upsertSettings.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(
        env.challengeSettingsService.upsertSettings.calls.map(_.submissionLimits) == List(SubmissionLimits.recommended),
      )
    },
    test("updateTenant delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateTenant.succeedsWith(())
        _ <- env.service.updateTenant(updateRequest)
      yield assertTrue(env.repository.updateTenant.calls == List((tenant1, "Updated Tenant A", None)))
    },
    test("deleteTenant delegates id to repository") {
      val env = new Env()

      for
        _ <- env.repository.deleteTenant.succeedsWith(())
        _ <- env.service.deleteTenant(tenant1)
      yield assertTrue(env.repository.deleteTenant.calls == List(tenant1))
    },
    test("sync reloads cache from repository") {
      val env = new Env()
      val refreshed = Vector(tenantRecord1, tenantRecord2)

      for
        _ <- env.repository.getAll.succeedsWith(refreshed)
        _ <- env.service.sync()
        cached <- env.cache.get
      yield assertTrue(cached == refreshed)
    },
  )