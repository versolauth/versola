package versola.central.configuration.forms

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.locales.LocaleRepository
import versola.util.DatabaseSpecBase
import zio.test.*

trait FormTranslationRepositorySpec extends DatabaseSpecBase[FormTranslationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val otp = FormId("otp")
  private val credential = FormId("credential")

  private def record(formId: FormId, locale: String, texts: (String, String)*) =
    FormTranslationRecord(formId = formId, locale = locale, texts = texts.toMap)

  override def testCases(env: FormTranslationRepositorySpec.Env) =
    List(
      test("upsert stores the texts and getAll returns them ordered by form and language") {
        for
          _ <- env.repository.upsert(record(otp, "ru", "title" -> "Код"))
          _ <- env.repository.upsert(record(credential, "ru", "title" -> "Вход"))
          _ <- env.repository.upsert(record(otp, "kk", "title" -> "Код енгізіңіз"))
          all <- env.repository.getAll
        yield assertTrue(all.map(r => (r.formId, r.locale)) == Vector((credential, "ru"), (otp, "kk"), (otp, "ru")))
      },
      test("upsert replaces the texts of a form in a language rather than merging them") {
        for
          _ <- env.repository.upsert(record(otp, "ru", "title" -> "Код", "hint" -> "Подсказка"))
          _ <- env.repository.upsert(record(otp, "ru", "title" -> "Новый код"))
          all <- env.repository.getAll
        yield assertTrue(all == Vector(record(otp, "ru", "title" -> "Новый код")))
      },
      test("upsert keeps non-ASCII texts and placeholders as written") {
        val text = "Қайта жіберу {seconds} с кейін — «код»"
        for
          _ <- env.repository.upsert(record(otp, "ru", "resend_in" -> text))
          all <- env.repository.getAll
        yield assertTrue(all.head.texts == Map("resend_in" -> text))
      },
      test("upsert for a language that is not registered is refused, nothing is stored") {
        for
          result <- env.repository.upsert(record(otp, "xx", "title" -> "?")).either
          all <- env.repository.getAll
        yield assertTrue(result.isLeft, all.isEmpty)
      },
      test("delete removes one form's translation into one language and says whether there was one") {
        for
          _ <- env.repository.upsert(record(otp, "ru", "title" -> "Код"))
          _ <- env.repository.upsert(record(otp, "kk", "title" -> "Код енгізіңіз"))
          removed <- env.repository.delete(otp, "ru")
          again <- env.repository.delete(otp, "ru")
          all <- env.repository.getAll
        yield assertTrue(removed, !again, all.map(_.locale) == Vector("kk"))
      },
    )

object FormTranslationRepositorySpec:
  case class Env(repository: FormTranslationRepository, locales: LocaleRepository, xa: TransactorZIO)
