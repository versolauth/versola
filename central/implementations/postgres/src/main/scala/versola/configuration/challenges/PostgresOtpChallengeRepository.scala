package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.challenges.{OtpChallengeRepository, OtpTemplateChannel, OtpTemplatePurpose, OtpTemplateRecord}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresOtpChallengeRepository(xa: TransactorZIO) extends OtpChallengeRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[OtpTemplatePurpose] = DbCodec.StringCodec.biMap(value => OtpTemplatePurpose.values.find(_.toString == value).get, _.toString)
  given DbCodec[OtpTemplateChannel] = DbCodec.StringCodec.biMap(value => OtpTemplateChannel.values.find(_.toString == value).get, _.toString)
  given DbCodec[OtpTemplateRecord] = DbCodec.derived[OtpTemplateRecord]

  override def getAll: Task[Vector[OtpTemplateRecord]] =
    xa.connectMeasured("get-all-otp-templates"):
      sql"""SELECT id, tenant_id, localizations, purpose, channel FROM otp_templates ORDER BY tenant_id, id"""
        .query[OtpTemplateRecord].run()

  override def find(id: String, tenantId: TenantId, purpose: OtpTemplatePurpose, channel: OtpTemplateChannel): Task[Option[OtpTemplateRecord]] =
    xa.connectMeasured("find-otp-template"):
      sql"""SELECT id, tenant_id, localizations, purpose, channel FROM otp_templates WHERE id = $id AND tenant_id = $tenantId AND purpose = $purpose AND channel = $channel"""
        .query[OtpTemplateRecord].run().headOption

  override def upsertTemplate(record: OtpTemplateRecord): Task[Unit] =
    xa.connectMeasured("upsert-otp-template"):
      sql"""
        INSERT INTO otp_templates (id, tenant_id, localizations, purpose, channel)
        VALUES (${record.id}, ${record.tenantId}, ${record.localizations}, ${record.purpose}, ${record.channel})
        ON CONFLICT (id, tenant_id, purpose, channel) DO UPDATE SET
          localizations = EXCLUDED.localizations,
          purpose = EXCLUDED.purpose,
          channel = EXCLUDED.channel
      """.update.run()
    .unit

  override def deleteTemplate(id: String, tenantId: TenantId, purpose: OtpTemplatePurpose, channel: OtpTemplateChannel): Task[Unit] =
    xa.connectMeasured("delete-otp-template"):
      sql"""DELETE FROM otp_templates WHERE id = $id AND tenant_id = $tenantId AND purpose = $purpose AND channel = $channel""".update.run()
    .unit

object PostgresOtpChallengeRepository:
  def live: ZLayer[TransactorZIO, Nothing, OtpChallengeRepository] =
    ZLayer.fromFunction(PostgresOtpChallengeRepository(_))
