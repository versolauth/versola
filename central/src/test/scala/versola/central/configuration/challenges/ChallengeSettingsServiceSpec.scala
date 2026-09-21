package versola.central.configuration.challenges

import versola.central.configuration.jwks.{JwksRecord, JwksRepository}
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.util.Secret
import zio.json.ast.Json
import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

object ChallengeSettingsServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val settings = ChallengeSettingsRecord(
    tenantId = tenantId,
    allowedPrefixes = List.empty,
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
    postLogoutRedirectUris = List.empty,
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
  )

  /** A key as central stores one it generated: published with an `alg`, private half kept. */
  private def signableKey(kid: String, alg: String) = JwksRecord(
    kid = kid,
    jwk = Json.Obj("kid" -> Json.Str(kid), "kty" -> Json.Str("RSA"), "alg" -> Json.Str(alg)),
    privateKey = Some(Secret.fromString("encrypted-pkcs8")),
  )

  class Env(initial: Vector[ChallengeSettingsRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ChallengeSettingsRepository]
    val jwksRepository = stub[JwksRepository]
    val service = ChallengeSettingsService.Impl(cache, repository, jwksRepository)

  def spec = suite("ChallengeSettingsService")(
    test("getSettings returns None when cache is empty") {
      val env = Env()
      for result <- env.service.getSettings(tenantId)
      yield assertTrue(result.isEmpty)
    },
    test("getSettings returns matching settings for tenant") {
      val env = Env(Vector(settings))
      for result <- env.service.getSettings(tenantId)
      yield assertTrue(result.contains(settings))
    },
    test("getSettings returns None for unknown tenant") {
      val env = Env(Vector(settings))
      for result <- env.service.getSettings(otherTenantId)
      yield assertTrue(result.isEmpty)
    },
    test("upsertSettings delegates to repository") {
      val env = Env()
      for
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.upsertSettings(settings)
      yield assertTrue(env.repository.upsert.calls == List(settings))
    },
    // A tenant pointing at a key that cannot sign would not fail at write time and then not
    // fail at sign time either: auth would quietly fall back to its legacy configured key,
    // issuing tokens under an algorithm nobody selected. So the write is what has to fail.
    suite("signing key validation")(
      test("accepts a key central holds a private half and an alg for") {
        val env = Env()
        val selected = settings.copy(signingKeyId = Some("ps-kid"))
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(signableKey("ps-kid", "PS256")))
          _ <- env.repository.upsert.succeedsWith(())
          _ <- env.service.upsertSettings(selected)
        yield assertTrue(env.repository.upsert.calls == List(selected))
      },
      test("rejects a kid that does not exist, without writing") {
        val env = Env()
        for
          _ <- env.jwksRepository.find.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("missing"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("rejects a verify-only kid, without writing") {
        val env = Env()
        val verifyOnly = signableKey("bootstrap-kid", "RS256").copy(privateKey = None)
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(verifyOnly))
          _ <- env.repository.upsert.succeedsWith(())
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("bootstrap-kid"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("rejects a kid published without a usable alg, without writing") {
        val env = Env()
        val noAlg = JwksRecord(
          kid = "no-alg",
          jwk = Json.Obj("kid" -> Json.Str("no-alg"), "kty" -> Json.Str("RSA")),
          privateKey = Some(Secret.fromString("encrypted-pkcs8")),
        )
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(noAlg))
          _ <- env.repository.upsert.succeedsWith(())
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("no-alg"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("a cleared selection is not validated -- it is the legacy fallback, not a key") {
        val env = Env()
        for
          _ <- env.repository.upsert.succeedsWith(())
          _ <- env.service.upsertSettings(settings.copy(signingKeyId = None))
        yield assertTrue(env.jwksRepository.find.calls.isEmpty)
      },
    ),
    test("sync removes settings on delete event") {
      val env = Env(Vector(settings))
      val event = SyncEvent.ChallengeSettingsUpdated(tenantId, SyncEvent.Op.DELETE)
      for
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached.isEmpty)
    },
    test("sync upserts fetched settings on non-delete event") {
      val env = Env(Vector.empty)
      val event = SyncEvent.ChallengeSettingsUpdated(tenantId, SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.findByTenant.succeedsWith(Some(settings))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached == Vector(settings))
    },
  )
