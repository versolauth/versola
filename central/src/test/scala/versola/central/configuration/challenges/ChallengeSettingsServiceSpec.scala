package versola.central.configuration.challenges

import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
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
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
  )

  class Env(initial: Vector[ChallengeSettingsRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ChallengeSettingsRepository]
    val service = ChallengeSettingsService.Impl(cache, repository)

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
