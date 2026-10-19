package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.ConfigSnapshot
import zio.{Task, ZLayer}

import java.time.Instant

private case class ConfigSnapshotRow(key: String, body: Array[Byte], savedAt: Instant, mac: Array[Byte])

/** One table per service (`auth_config_snapshots`, `edge_config_snapshots`): services that share
  * a schema must not read each other's records, which are authenticated under different keys.
  */
class PostgresConfigSnapshotRepository(xa: TransactorZIO, table: String) extends ConfigSnapshot.Repository, BasicCodecs:

  private given DbCodec[ConfigSnapshotRow] = DbCodec.derived

  private val tableName = SqlLiteral(table)

  override def find(key: String): Task[Option[ConfigSnapshot.Record]] =
    xa.connectMeasured("find-config-snapshot"):
      sql"SELECT key, body, saved_at, mac FROM $tableName WHERE key = $key"
        .query[ConfigSnapshotRow]
        .run()
        .headOption
        .map(row => ConfigSnapshot.Record(row.key, row.body, row.savedAt, row.mac))

  override def save(record: ConfigSnapshot.Record): Task[Unit] =
    xa.transactMeasured("save-config-snapshot"):
      sql"""
        INSERT INTO $tableName (key, body, saved_at, mac)
        VALUES (${record.key}, ${record.body}, ${record.savedAt}, ${record.mac})
        ON CONFLICT (key) DO UPDATE
        SET body = EXCLUDED.body, saved_at = EXCLUDED.saved_at, mac = EXCLUDED.mac
        WHERE $tableName.saved_at < EXCLUDED.saved_at
      """.update.run()
    .unit

object PostgresConfigSnapshotRepository:
  def live(table: String): ZLayer[TransactorZIO, Nothing, ConfigSnapshot.Repository] =
    ZLayer.fromFunction(PostgresConfigSnapshotRepository(_, table))
