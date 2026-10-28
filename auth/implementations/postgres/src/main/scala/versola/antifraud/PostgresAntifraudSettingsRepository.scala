package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Task, ZIO, ZLayer}

import java.time.Instant

class PostgresAntifraudSettingsRepository(xa: TransactorZIO) extends AntifraudSettingsRepository, BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec

  override def put(tuning: Tuning, actor: String, at: Instant): Task[Unit] =
    val json = tuning.toJson
    xa.transactMeasured("put-antifraud-settings"):
      sql"""
        INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, $json, $actor, $at)
        ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
      """.update.run()
      sql"UPDATE af_settings_version SET version = version + 1 WHERE id = 1".update.run()
      ()

  override def find: Task[Option[Tuning]] =
    xa.connectMeasured("find-antifraud-settings"):
      sql"SELECT settings FROM af_settings WHERE id = 1".query[String].run().headOption
    .flatMap:
      case None => ZIO.none
      case Some(json) =>
        ZIO.fromEither(json.fromJson[Tuning]).mapError(message => RuntimeException(s"stored antifraud settings are not valid: $message")).asSome

  override def version: Task[Long] =
    xa.connectMeasured("antifraud-settings-version"):
      sql"SELECT version FROM af_settings_version WHERE id = 1".query[Long].run().head

object PostgresAntifraudSettingsRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudSettingsRepository] =
    ZLayer.fromFunction(PostgresAntifraudSettingsRepository(_))
