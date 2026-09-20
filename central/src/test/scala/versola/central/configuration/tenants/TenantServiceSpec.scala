package versola.central.configuration.tenants

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, PasskeySettings, SubmissionLimits}
import versola.central.configuration.jwks.{JwksRecord, JwksRepository}
import versola.util.{ReloadingCache, Secret}
import zio.json.ast.Json
import zio.*
import zio.test.*

object TenantServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenant1 = TenantId("tenant-a")
  private val tenant2 = TenantId("tenant-b")

  private val tenantRecord1 = TenantRecord(tenant1, "Tenant A", None)
  private val tenantRecord2 = TenantRecord(tenant2, "Tenant B", None)

  private val createRequest = CreateTenantRequest(tenant1, "Tenant A", None)

  private def signableKey(kid: String, alg: String) = JwksRecord(
    kid = kid,
    jwk = Json.Obj("kid" -> Json.Str(kid), "kty" -> Json.Str("RSA"), "alg" -> Json.Str(alg)),
    privateKey = Some(Secret.fromString("encrypted-pkcs8")),
  )
  private val updateRequest = UpdateTenantRequest(tenant1, "Updated Tenant A", None)

  class Env(initial: Vector[TenantRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[TenantRepository]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val jwksRepository = stub[JwksRepository]
    val service =
      TenantService.Impl(cache, repository, challengeSettingsService, jwksRepository, TestCentralConfig.config)

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
        _ <- env.jwksRepository.getAll.succeedsWith(Vector.empty)
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
            requireDpopNonce = false,
            mtlsCertificateHeader = None,
            mtlsCertificateEncoding = None,
            signingKeyId = None,
          )),
      )
    },
    test("createTenant always seeds the recommended submission limits -- the request has no override for them") {
      val env = new Env()

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.jwksRepository.getAll.succeedsWith(Vector.empty)
        _ <- env.challengeSettingsService.upsertSettings.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(
        env.challengeSettingsService.upsertSettings.calls.map(_.submissionLimits) == List(SubmissionLimits.recommended),
      )
    },
    // Left unset, a new tenant would fall back to auth's legacy configured key, signing
    // under whatever algorithm that key is rather than the one the deployment prefers.
    test("createTenant starts the tenant on the key the deployment prefers") {
      val env = new Env()
      val keys = Vector(
        signableKey("rs-kid", "RS256"),
        signableKey("ps-kid", "PS256"),
        signableKey("es-kid", "ES256"),
      )

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.jwksRepository.getAll.succeedsWith(keys)
        _ <- env.challengeSettingsService.upsertSettings.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(
        env.challengeSettingsService.upsertSettings.calls.map(_.signingKeyId) == List(Some("ps-kid")),
      )
    },
    // The state of a deployment seeded from `bootstrap.jwks`: central holds no private half
    // for anything, so there is nothing to select and auth's own key is all there is.
    test("createTenant selects nothing when every stored key is verify-only") {
      val env = new Env()
      val verifyOnly = signableKey("ps-kid", "PS256").copy(privateKey = None)

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.jwksRepository.getAll.succeedsWith(Vector(verifyOnly))
        _ <- env.challengeSettingsService.upsertSettings.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(
        env.challengeSettingsService.upsertSettings.calls.map(_.signingKeyId) == List(None),
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