package versola.central.configuration.challenges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.test.*

trait OtpChallengeRepositorySpec extends DatabaseSpecBase[OtpChallengeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val rec1 = OtpTemplateRecord("tmpl-1", tenantA, Map("en" -> "Code: {{code}}"))
  private val rec2 = OtpTemplateRecord("tmpl-2", tenantA, Map("en" -> "Your code: {{code}}", "ru" -> "Ваш код: {{code}}"))
  private val rec3 = OtpTemplateRecord("tmpl-3", tenantB, Map("en" -> "Login code: {{code}}"))

  override def testCases(env: OtpChallengeRepositorySpec.Env) =
    List(
      test("upsertTemplate persists record and find returns it") {
        for
          _ <- env.repository.upsertTemplate(rec1)
          found <- env.repository.find(rec1.id, rec1.tenantId)
        yield assertTrue(found == Some(rec1))
      },
      test("upsertTemplate on conflict updates localizations") {
        val updated = rec1.copy(localizations = Map("en" -> "Updated: {{code}}", "fr" -> "Code: {{code}}"))
        for
          _ <- env.repository.upsertTemplate(rec1)
          _ <- env.repository.upsertTemplate(updated)
          found <- env.repository.find(rec1.id, rec1.tenantId)
        yield assertTrue(found == Some(updated))
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
          found <- env.repository.find("missing", tenantA)
        yield assertTrue(found.isEmpty)
      },
      test("deleteTemplate removes the record") {
        for
          _ <- env.repository.upsertTemplate(rec1)
          _ <- env.repository.upsertTemplate(rec2)
          _ <- env.repository.deleteTemplate(rec1.id, rec1.tenantId)
          deleted <- env.repository.find(rec1.id, rec1.tenantId)
          remaining <- env.repository.getAll
        yield assertTrue(deleted.isEmpty, remaining == Vector(rec2))
      },
    )

object OtpChallengeRepositorySpec:
  case class Env(repository: OtpChallengeRepository)
