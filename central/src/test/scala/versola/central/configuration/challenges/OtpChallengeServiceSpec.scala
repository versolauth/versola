package versola.central.configuration.challenges

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object OtpChallengeServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val rec1 = OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}"), purpose = "otp")
  private val rec2 = OtpTemplateRecord("tmpl-2", tenantB, Map("en" -> "Your code: {{code}}"), purpose = "otp")
  private val rec3 = OtpTemplateRecord("tmpl-3", tenantA, Map("en" -> "Another: {{code}}"), purpose = "otp")
  private val rec1Updated = rec1.copy(localizations = Map("en" -> "Updated: {{code}}"))

  class Env(initial: Vector[OtpTemplateRecord] = Vector.empty):
    val cache         = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository    = stub[OtpChallengeRepository]
    val localeService = stub[LocaleService]
    val service       = OtpChallengeService.Impl(cache, repository, localeService)

  def spec = suite("OtpChallengeService")(
    test("getTemplates returns only records for given tenant") {
      val env = Env(Vector(rec1, rec2, rec3))
      for
        result <- env.service.getTemplates(tenantA)
      yield assertTrue(result == Vector(rec1, rec3))
    },
    test("getAllTemplates returns all cached records") {
      val env = Env(Vector(rec1, rec2))
      for
        result <- env.service.getAllTemplates
      yield assertTrue(result == Vector(rec1, rec2))
    },
    test("upsertTemplate delegates to repository without touching cache") {
      val env = Env(Vector(rec1))
      for
        _ <- env.repository.upsertTemplate.succeedsWith(())
        _ <- env.service.upsertTemplate(rec2)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsertTemplate.calls == List(rec2),
        cached == Vector(rec1),
      )
    },
    test("deleteTemplate delegates to repository without touching cache") {
      val env = Env(Vector(rec1, rec2))
      for
        _ <- env.repository.deleteTemplate.succeedsWith(())
        _ <- env.service.deleteTemplate("tmpl-1", tenantA)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.deleteTemplate.calls == List(("tmpl-1", tenantA)),
        cached == Vector(rec1, rec2),
      )
    },
    test("sync INSERT adds new record to cache") {
      val env = Env(Vector(rec1))
      val event = SyncEvent.OtpTemplatesUpdated(tenantB, "tmpl-2", SyncEvent.Op.INSERT)
      for
        _ <- env.repository.find.succeedsWith(Some(rec2))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached.contains(rec2), cached.contains(rec1))
    },
    test("sync UPDATE replaces existing record in cache") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.find.succeedsWith(Some(rec1Updated))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(
        cached.contains(rec1Updated),
        !cached.contains(rec1),
      )
    },
    test("sync DELETE removes record from cache") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", SyncEvent.Op.DELETE)
      for
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
    test("sync non-delete removes record when repository returns None") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
    test("getSyncTemplates strips localizations of inactive locales") {
      import versola.central.configuration.locales.LocaleRecord
      val template = OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}", "fr" -> "Code: {{code}}"), purpose = "otp")
      val env = Env(Vector(template))
      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncTemplates
      yield assertTrue(result.head.localizations == Map("en" -> "Code: {{code}}"))
    },
  )
