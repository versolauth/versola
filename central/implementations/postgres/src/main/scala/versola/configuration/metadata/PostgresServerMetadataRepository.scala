package versola.configuration.metadata

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.metadata.{ServerMetadataRecord, ServerMetadataRepository}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresServerMetadataRepository(xa: TransactorZIO) extends ServerMetadataRepository, BasicCodecs:

  given DbCodec[Json.Obj] = jsonCodec[Json.Obj]
  given DbCodec[ServerMetadataRecord] = DbCodec.derived

  override def get: Task[Option[ServerMetadataRecord]] =
    xa.connectMeasured("get-server-metadata"):
      sql"""
        SELECT id, metadata
        FROM server_metadata
        WHERE id = 'default'
      """.query[ServerMetadataRecord].run().headOption

  override def upsert(metadata: Json.Obj): Task[Unit] =
    xa.connectMeasured("upsert-server-metadata"):
      sql"""
        INSERT INTO server_metadata (id, metadata)
        VALUES ('default', $metadata)
        ON CONFLICT (id) DO UPDATE SET
          metadata = EXCLUDED.metadata
      """.update.run()
    .unit

object PostgresServerMetadataRepository:
  def live: ZLayer[TransactorZIO, Nothing, ServerMetadataRepository] =
    ZLayer.fromFunction(PostgresServerMetadataRepository(_))
