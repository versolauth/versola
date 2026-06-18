package versola.central.configuration.challenges

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object PhoneChallengeServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val rec1 = PhoneSettingsRecord(tenantA, List("+1", "+44"), Some("^.{8,}$"))
  private val rec2 = PhoneSettingsRecord(tenantB, List("+7"), None)
  private val rec1Updated = rec1.copy(allowedPrefixes = List("+1"), passwordRegex = Some("^.{12,}$"))

  class Env(initial: Vector[PhoneSettingsRecord] = Vector.empty):
    val cache      = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[PhoneChallengeRepository]
    val service    = PhoneChallengeService.Impl(cache, repository)

  def spec = suite("PhoneChallengeService")(
    test("getSettings returns the record for the given tenant") {
      val env = Env(Vector(rec1, rec2))
      for
        result <- env.service.getSettings(tenantA)
      yield assertTrue(result == rec1)
    },
    test("getSettings returns empty default when tenant is missing") {
      val env = Env(Vector(rec1))
      for
        result <- env.service.getSettings(tenantB)
      yield assertTrue(result == PhoneSettingsRecord(tenantB, Nil, None))
    },
    test("getAllSettings returns all cached records") {
      val env = Env(Vector(rec1, rec2))
      for
        result <- env.service.getAllSettings
      yield assertTrue(result == Vector(rec1, rec2))
    },
    test("upsertSettings persists and refreshes the cache from the repository") {
      val env = Env(Vector(rec1))
      for
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.repository.getAll.succeedsWith(Vector(rec1, rec2))
        _ <- env.service.upsertSettings(rec2)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsert.calls == List(rec2),
        cached == Vector(rec1, rec2),
      )
    },
    test("sync INSERT adds new record to cache") {
      val env = Env(Vector(rec1))
      val event = SyncEvent.PhoneSettingsUpdated(tenantB, SyncEvent.Op.INSERT)
      for
        _ <- env.repository.findByTenant.succeedsWith(Some(rec2))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached.contains(rec2), cached.contains(rec1))
    },
    test("sync UPDATE replaces existing record in cache") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.PhoneSettingsUpdated(tenantA, SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.findByTenant.succeedsWith(Some(rec1Updated))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(
        cached.contains(rec1Updated),
        !cached.contains(rec1),
      )
    },
    test("sync DELETE removes record from cache") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.PhoneSettingsUpdated(tenantA, SyncEvent.Op.DELETE)
      for
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
    test("sync non-delete removes record when repository returns None") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.PhoneSettingsUpdated(tenantA, SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.findByTenant.succeedsWith(None)
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
  )
