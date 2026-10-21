package versola.configuration.forms

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.forms.{FormId, FormTranslationRecord, FormTranslationRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresFormTranslationRepository(xa: TransactorZIO) extends FormTranslationRepository, BasicCodecs:

  given DbCodec[FormId] = DbCodec.StringCodec.biMap(FormId(_), identity[String])
  given textsCodec: DbCodec[Map[String, String]] = jsonBCodec[Map[String, String]]
  given DbCodec[FormTranslationRecord] = DbCodec.derived

  override def getAll: Task[Vector[FormTranslationRecord]] =
    xa.connectMeasured("get-all-form-translations"):
      sql"""SELECT form_id, locale, texts FROM form_translations ORDER BY form_id, locale""".query[FormTranslationRecord].run()

  override def upsert(record: FormTranslationRecord): Task[Unit] =
    xa.connectMeasured("upsert-form-translation"):
      sql"""INSERT INTO form_translations (form_id, locale, texts) VALUES (${record.formId}, ${record.locale}, ${record.texts})
            ON CONFLICT (form_id, locale) DO UPDATE SET texts = EXCLUDED.texts""".update.run()
    .unit

  override def delete(formId: FormId, locale: String): Task[Boolean] =
    xa.connectMeasured("delete-form-translation"):
      sql"""DELETE FROM form_translations WHERE form_id = $formId AND locale = $locale""".update.run() > 0

object PostgresFormTranslationRepository:
  def live: ZLayer[TransactorZIO, Nothing, FormTranslationRepository] =
    ZLayer.fromFunction(PostgresFormTranslationRepository(_))
