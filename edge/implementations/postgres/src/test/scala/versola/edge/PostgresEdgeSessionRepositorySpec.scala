package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.session.EdgeSessionRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresEdgeSessionRepositorySpec extends PostgresSpec, EdgeSessionRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield EdgeSessionRepositorySpec.Env(PostgresEdgeSessionRepository(xa))

  override def beforeEach(env: EdgeSessionRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE edge_sessions".update.run())
    }.unit
