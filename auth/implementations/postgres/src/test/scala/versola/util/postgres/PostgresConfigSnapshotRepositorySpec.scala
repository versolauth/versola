package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.ConfigSnapshotRepositorySpec
import zio.{ZIO, ZLayer}

object PostgresConfigSnapshotRepositorySpec extends PostgresSpec, ConfigSnapshotRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ConfigSnapshotRepositorySpec.Env(PostgresConfigSnapshotRepository(xa, "auth_config_snapshots"))

  override def beforeEach(env: ConfigSnapshotRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE auth_config_snapshots".update.run()))
