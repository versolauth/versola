package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.challenges.{OtpChallengeRepository, OtpTemplateRecord}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresOtpChallengeRepository(xa: TransactorZIO) extends OtpChallengeRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[OtpTemplateRecord] = DbCodec.derived[OtpTemplateRecord]

  override def getAll: Task[Vector[OtpTemplateRecord]] =
    xa.trackedConnect:
      sql"""SELECT id, tenant_id, localizations FROM otp_templates ORDER BY tenant_id, id"""
        .query[OtpTemplateRecord].run()

  override def find(id: String, tenantId: TenantId): Task[Option[OtpTemplateRecord]] =
    xa.trackedConnect:
      sql"""SELECT id, tenant_id, localizations FROM otp_templates WHERE id = $id AND tenant_id = $tenantId"""
        .query[OtpTemplateRecord].run().headOption

  override def upsertTemplate(record: OtpTemplateRecord): Task[Unit] =
    xa.trackedConnect:
      sql"""
        INSERT INTO otp_templates (id, tenant_id, localizations)
        VALUES (${record.id}, ${record.tenantId}, ${record.localizations})
        ON CONFLICT (id, tenant_id) DO UPDATE SET
          localizations = EXCLUDED.localizations
      """.update.run()
    .unit

  override def deleteTemplate(id: String, tenantId: TenantId): Task[Unit] =
    xa.trackedConnect:
      sql"""DELETE FROM otp_templates WHERE id = $id AND tenant_id = $tenantId""".update.run()
    .unit

object PostgresOtpChallengeRepository:
  def live: ZLayer[TransactorZIO, Nothing, OtpChallengeRepository] =
    ZLayer.fromFunction(PostgresOtpChallengeRepository(_))
