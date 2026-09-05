package versola.configuration.metadata

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.metadata.ServerMetadataRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresServerMetadataRepositorySpec extends PostgresSpec, ServerMetadataRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ServerMetadataRepositorySpec.Env(PostgresServerMetadataRepository(xa))

  override def beforeEach(env: ServerMetadataRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE server_metadata".update.run())
    }.unit
