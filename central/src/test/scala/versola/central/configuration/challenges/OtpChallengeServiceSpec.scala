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

  private val rec1 =
    OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec2 =
    OtpTemplateRecord("tmpl-2", tenantB, Map("en" -> "Your code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec3 =
    OtpTemplateRecord("tmpl-3", tenantA, Map("en" -> "Another: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec1Updated = rec1.copy(localizations = Map("en" -> "Updated: {{code}}"))

  class Env(initial: Vector[OtpTemplateRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OtpChallengeRepository]
    val localeService = stub[LocaleService]
    val service = OtpChallengeService.Impl(cache, repository, localeService)

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
        _ <- env.service.upsertTemplate(rec2, keepStoredSubjects = false)
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
        validResult <- env.service.upsertTemplate(valid, keepStoredSubjects = false).either
        invalidResult <- env.service.upsertTemplate(invalid, keepStoredSubjects = false).either
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
        smsResult <- env.service.upsertTemplate(passwordSms, keepStoredSubjects = false).either
        emailResult <- env.service.upsertTemplate(passwordEmail, keepStoredSubjects = false).either
        invalidResult <- env.service.upsertTemplate(invalidPasswordEmail, keepStoredSubjects = false).either
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
    test("accepts a subject for an email template and refuses a blank, multi-line or overlong one, or one for an SMS template") {
      val env = Env()
      val email = OtpTemplateRecord(
        "email-template",
        tenantA,
        Map("en" -> "<html><body>Code: {{code}}</body></html>"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.email,
        subjects = Map("en" -> "{{code}} for {{appName}}"),
      )
      def check(record: OtpTemplateRecord) = env.service.upsertTemplate(record, keepStoredSubjects = false).either.map(_.left.map(_.getMessage))
      for
        _ <- env.repository.upsertTemplate.succeedsWith(())
        valid <- check(email)
        blank <- check(email.copy(subjects = Map("en" -> "  ")))
        multiline <- check(email.copy(subjects = Map("en" -> "Code\r\nBcc: evil@example.com")))
        overlong <- check(email.copy(subjects = Map("en" -> "x" * (OtpChallengeService.MaxSubjectLength + 1))))
        longest <- check(email.copy(subjects = Map("en" -> "x" * OtpChallengeService.MaxSubjectLength)))
        sms <- check(email.copy(channel = OtpTemplateChannel.sms, localizations = Map("en" -> "Code {{code}}")))
        smsWithout <- check(email.copy(channel = OtpTemplateChannel.sms, localizations = Map("en" -> "Code {{code}}"), subjects = Map.empty))
      yield assertTrue(
        valid == Right(()),
        blank == Left("Subject en must not be empty"),
        multiline == Left("Subject en must be one line"),
        overlong.isLeft,
        longest == Right(()),
        sms == Left("Only email templates have a subject"),
        smsWithout == Right(()),
        env.repository.upsertTemplate.calls.length == 3,
      )
    },
    test("a save that names no subjects keeps the stored ones, and one that names an empty map clears them") {
      val env = Env()
      val stored = OtpTemplateRecord(
        "email-template",
        tenantA,
        Map("en" -> "<html><body>Code: {{code}}</body></html>"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.email,
        subjects = Map("en" -> "Your code"),
      )
      val edited = stored.copy(localizations = Map("en" -> "<html><body>New {{code}}</body></html>"), subjects = Map.empty)
      for
        _ <- env.repository.find.succeedsWith(Some(stored))
        _ <- env.repository.upsertTemplate.succeedsWith(())
        _ <- env.service.upsertTemplate(edited, keepStoredSubjects = true)
        _ <- env.service.upsertTemplate(edited, keepStoredSubjects = false)
        saved = env.repository.upsertTemplate.calls
      yield assertTrue(
        saved.map(_.subjects) == List(Map("en" -> "Your code"), Map.empty[String, String]),
        saved.forall(_.localizations == edited.localizations),
        env.repository.find.calls.length == 1,
      )
    },
    test("a save that names no subjects for a template that is not stored yet stores none") {
      val env = Env()
      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.repository.upsertTemplate.succeedsWith(())
        _ <- env.service.upsertTemplate(rec1, keepStoredSubjects = true)
      yield assertTrue(env.repository.upsertTemplate.calls == List(rec1))
    },
    test("getSyncTemplates strips the subjects of inactive locales along with their localizations") {
      import versola.central.configuration.locales.LocaleRecord
      val template = OtpTemplateRecord(
        "tmpl-1",
        tenantA,
        Map("en" -> "<p>{{code}}</p>", "fr" -> "<p>{{code}}</p>"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.email,
        subjects = Map("en" -> "Your code", "fr" -> "Votre code"),
      )
      val env = Env(Vector(template))
      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncTemplates
      yield assertTrue(result.head.subjects == Map("en" -> "Your code"))
    },
    test("getSyncTemplates strips localizations of inactive locales") {
      import versola.central.configuration.locales.LocaleRecord
      val template = OtpTemplateRecord(
        "tmpl-1",
        tenantA,
        Map("en" -> "Code: {{code}}", "fr" -> "Code: {{code}}"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.sms,
      )
      val env = Env(Vector(template))
      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncTemplates
      yield assertTrue(result.head.localizations == Map("en" -> "Code: {{code}}"))
    },
  )
