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

  private val rec1 = OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec2 = OtpTemplateRecord("tmpl-2", tenantB, Map("en" -> "Your code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec3 = OtpTemplateRecord("tmpl-3", tenantA, Map("en" -> "Another: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
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
    test("validates email templates before persisting them") {
      val env = Env()
      val valid = OtpTemplateRecord(
        "email-template",
        tenantA,
        Map("en" -> "<html><body>Code: {{code}}</body></html>"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.email,
      )
      val invalid = valid.copy(localizations = Map("en" -> "Code: {{code}}"))
      for
        _ <- env.repository.upsertTemplate.succeedsWith(())
        validResult <- env.service.upsertTemplate(valid).either
        invalidResult <- env.service.upsertTemplate(invalid).either
      yield assertTrue(validResult == Right(()), invalidResult.isLeft)
    },
    test("allows plain-text password SMS and requires HTML for password email") {
      val passwordSms = OtpTemplateRecord(
        "password-sms",
        tenantA,
        Map("en" -> "Password: {{password}}; expires in {{expiresHours}} hours"),
        purpose = OtpTemplatePurpose.password,
        channel = OtpTemplateChannel.sms,
      )
      val passwordEmail = passwordSms.copy(
        id = "password-email",
        localizations = Map("en" -> "<html><body>Password: {{password}}; expires in {{expiresHours}}</body></html>"),
        channel = OtpTemplateChannel.email,
      )
      val invalidPasswordEmail = passwordEmail.copy(localizations = passwordSms.localizations)
      for
        env <- ZIO.succeed(Env())
        _ <- env.repository.upsertTemplate.succeedsWith(())
        smsResult <- env.service.upsertTemplate(passwordSms).either
        emailResult <- env.service.upsertTemplate(passwordEmail).either
        invalidResult <- env.service.upsertTemplate(invalidPasswordEmail).either
      yield assertTrue(smsResult == Right(()), emailResult == Right(()), invalidResult.isLeft)
    },
    test("deleteTemplate delegates to repository without touching cache") {
      val env = Env(Vector(rec1, rec2))
      for
        _ <- env.repository.deleteTemplate.succeedsWith(())
        _ <- env.service.deleteTemplate("tmpl-1", tenantA, OtpTemplatePurpose.otp, OtpTemplateChannel.sms)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.deleteTemplate.calls == List(("tmpl-1", tenantA, OtpTemplatePurpose.otp, OtpTemplateChannel.sms)),
        cached == Vector(rec1, rec2),
      )
    },
    test("sync INSERT adds new record to cache") {
      val env = Env(Vector(rec1))
      val event = SyncEvent.OtpTemplatesUpdated(tenantB, "tmpl-2", OtpTemplatePurpose.otp, OtpTemplateChannel.sms, SyncEvent.Op.INSERT)
      for
        _ <- env.repository.find.succeedsWith(Some(rec2))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached.contains(rec2), cached.contains(rec1))
    },
    test("sync UPDATE replaces existing record in cache") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", OtpTemplatePurpose.otp, OtpTemplateChannel.sms, SyncEvent.Op.UPDATE)
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
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", OtpTemplatePurpose.otp, OtpTemplateChannel.sms, SyncEvent.Op.DELETE)
      for
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
    test("sync non-delete removes record when repository returns None") {
      val env = Env(Vector(rec1, rec2))
      val event = SyncEvent.OtpTemplatesUpdated(tenantA, "tmpl-1", OtpTemplatePurpose.otp, OtpTemplateChannel.sms, SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(!cached.contains(rec1), cached.contains(rec2))
    },
    test("getSyncTemplates strips localizations of inactive locales") {
      import versola.central.configuration.locales.LocaleRecord
      val template = OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}", "fr" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
      val env = Env(Vector(template))
      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncTemplates
      yield assertTrue(result.head.localizations == Map("en" -> "Code: {{code}}"))
    },
  )
