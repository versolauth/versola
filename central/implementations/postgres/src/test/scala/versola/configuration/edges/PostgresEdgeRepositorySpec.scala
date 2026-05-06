package versola.configuration.edges

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.EdgeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresEdgeRepositorySpec extends PostgresSpec, EdgeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield EdgeRepositorySpec.Env(PostgresEdgeRepository(xa))

  override def beforeEach(env: EdgeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run())
    }.unit
