package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.central.configuration.challenges.{PhoneChallengeRepository, PhoneSettingsRecord}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresPhoneChallengeRepository(xa: TransactorZIO) extends PhoneChallengeRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[List[String]] = PgCodec.ListCodec[String]
  given DbCodec[PhoneSettingsRecord] = DbCodec.derived

  override def getAll: Task[Vector[PhoneSettingsRecord]] =
    xa.connect:
      sql"""SELECT tenant_id, allowed_prefixes FROM phone_settings ORDER BY tenant_id"""
        .query[PhoneSettingsRecord].run()

  override def findByTenant(tenantId: TenantId): Task[Option[PhoneSettingsRecord]] =
    xa.connect:
      sql"""SELECT tenant_id, allowed_prefixes FROM phone_settings WHERE tenant_id = $tenantId"""
        .query[PhoneSettingsRecord].run()
        .headOption

  override def upsert(record: PhoneSettingsRecord): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO phone_settings (tenant_id, allowed_prefixes)
        VALUES (${record.tenantId}, ${record.allowedPrefixes})
        ON CONFLICT (tenant_id) DO UPDATE SET
          allowed_prefixes = EXCLUDED.allowed_prefixes
      """.update.run()
    .unit

object PostgresPhoneChallengeRepository:
  def live: ZLayer[TransactorZIO, Nothing, PhoneChallengeRepository] =
    ZLayer.fromFunction(PostgresPhoneChallengeRepository(_))
