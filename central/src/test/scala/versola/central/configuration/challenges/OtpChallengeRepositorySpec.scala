package versola.central.configuration.challenges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.test.*

trait OtpChallengeRepositorySpec extends DatabaseSpecBase[OtpChallengeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val rec1 =
    OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)
  private val rec2 = OtpTemplateRecord(
    "tmpl-2",
    tenantA,
    Map("en" -> "Your code: {{code}}", "ru" -> "Ваш код: {{code}}"),
    purpose = OtpTemplatePurpose.otp,
    channel = OtpTemplateChannel.sms,
  )
  private val rec3 =
    OtpTemplateRecord("tmpl-3", tenantB, Map("en" -> "Login code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)

  override def testCases(env: OtpChallengeRepositorySpec.Env) =
    List(
      test("upsertTemplate persists record and find returns it") {
        for
          _ <- env.repository.upsertTemplate(rec1)
          found <- env.repository.find(rec1.id, rec1.tenantId, rec1.purpose, rec1.channel)
        yield assertTrue(found == Some(rec1))
      },
      test("upsertTemplate on conflict updates localizations") {
        val updated = rec1.copy(localizations = Map("en" -> "Updated: {{code}}", "fr" -> "Code: {{code}}"))
        for
          _ <- env.repository.upsertTemplate(rec1)
          _ <- env.repository.upsertTemplate(updated)
          found <- env.repository.find(rec1.id, rec1.tenantId, rec1.purpose, rec1.channel)
        yield assertTrue(found == Some(updated))
      },
      test("subjects are stored with the template, replaced on update, and empty for a template stored without them") {
        val email = OtpTemplateRecord(
          "email-subject",
          tenantA,
          Map("en" -> "<p>{{code}}</p>", "ru" -> "<p>{{code}}</p>"),
          purpose = OtpTemplatePurpose.otp,
          channel = OtpTemplateChannel.email,
          subjects = Map("en" -> "Your code", "ru" -> "Ваш код"),
        )
        for
          _ <- env.repository.upsertTemplate(email)
          stored <- env.repository.find(email.id, email.tenantId, email.purpose, email.channel)
          _ <- env.repository.upsertTemplate(email.copy(subjects = Map("en" -> "Code")))
          replaced <- env.repository.find(email.id, email.tenantId, email.purpose, email.channel)
          _ <- env.repository.upsertTemplate(email.copy(subjects = Map.empty))
          cleared <- env.repository.find(email.id, email.tenantId, email.purpose, email.channel)
          _ <- env.repository.upsertTemplate(rec1)
          plain <- env.repository.find(rec1.id, rec1.tenantId, rec1.purpose, rec1.channel)
        yield assertTrue(
          stored == Some(email),
          replaced.map(_.subjects) == Some(Map("en" -> "Code")),
          cleared.map(_.subjects) == Some(Map.empty[String, String]),
          plain.map(_.subjects) == Some(Map.empty[String, String]),
        )
      },
      test("same id can be used for different template purposes") {
        val otpEmail = rec1.copy(channel = OtpTemplateChannel.email)
        val passwordEmail = OtpTemplateRecord(
          rec1.id,
          rec1.tenantId,
          Map("en" -> "Password: {{password}}; expires in {{expiresHours}} hours"),
          purpose = OtpTemplatePurpose.password,
          channel = OtpTemplateChannel.email,
        )
        for
          _ <- env.repository.upsertTemplate(otpEmail)
          _ <- env.repository.upsertTemplate(passwordEmail)
          foundOtp <- env.repository.find(otpEmail.id, otpEmail.tenantId, otpEmail.purpose, otpEmail.channel)
          foundPassword <- env.repository.find(passwordEmail.id, passwordEmail.tenantId, passwordEmail.purpose, passwordEmail.channel)
        yield assertTrue(foundOtp == Some(otpEmail), foundPassword == Some(passwordEmail))
      },
      test("getAll returns all templates ordered by tenant and id") {
        for
          _ <- env.repository.upsertTemplate(rec3)
          _ <- env.repository.upsertTemplate(rec1)
          _ <- env.repository.upsertTemplate(rec2)
          all <- env.repository.getAll
        yield assertTrue(all == Vector(rec1, rec2, rec3))
      },
      test("find returns None for missing template") {
        for
          found <- env.repository.find("missing", tenantA, OtpTemplatePurpose.otp, OtpTemplateChannel.sms)
        yield assertTrue(found.isEmpty)
      },
      test("deleteTemplate removes the record") {
        for
          _ <- env.repository.upsertTemplate(rec1)
          _ <- env.repository.upsertTemplate(rec2)
          _ <- env.repository.deleteTemplate(rec1.id, rec1.tenantId, rec1.purpose, rec1.channel)
          deleted <- env.repository.find(rec1.id, rec1.tenantId, rec1.purpose, rec1.channel)
          remaining <- env.repository.getAll
        yield assertTrue(deleted.isEmpty, remaining == Vector(rec2))
      },
    )

object OtpChallengeRepositorySpec:
  case class Env(repository: OtpChallengeRepository)
