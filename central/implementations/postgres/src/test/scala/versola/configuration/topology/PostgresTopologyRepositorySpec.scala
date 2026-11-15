package versola.configuration.topology

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.topology.TopologyRepositorySpec
import versola.configuration.edges.PostgresEdgeRepository
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresTopologyRepositorySpec extends PostgresSpec, TopologyRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield TopologyRepositorySpec.Env(repository = PostgresTopologyRepository(xa), edges = PostgresEdgeRepository(xa))

  override def beforeEach(env: TopologyRepositorySpec.Env) =
    ZIO
      .serviceWithZIO[TransactorZIO](_.connect:
        sql"DELETE FROM cells WHERE id LIKE 'topology-spec-%'".update.run()
        sql"DELETE FROM edges WHERE id LIKE 'topology-spec-%'".update.run()
        sql"DELETE FROM regions WHERE id LIKE 'topology-spec-%'".update.run()
        sql"DELETE FROM partitions WHERE id LIKE 'topology-spec-%'".update.run())
      .unit
