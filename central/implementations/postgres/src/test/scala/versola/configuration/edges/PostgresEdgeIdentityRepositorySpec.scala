package versola.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.{EdgeGroup, EdgeGroupKind, EdgeGroupRecord, EdgeIdentityRepositorySpec}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

object PostgresEdgeIdentityRepositorySpec extends PostgresSpec, EdgeIdentityRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield EdgeIdentityRepositorySpec.Env(
        edges = PostgresEdgeRepository(xa),
        groups = PostgresEdgeGroupRepository(xa),
        tokens = PostgresEdgeEnrollmentTokenRepository(xa),
        certificates = PostgresEdgeCertificateRepository(xa),
        // The foreign key of `edges.group_id`, by the table it names or by what Postgres calls the violation.
        isUnknownGroup = failure => failure.getMessage.contains("edge_groups") || failure.getMessage.contains("violates foreign key"),
      )

  override def beforeEach(env: EdgeIdentityRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect:
        sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run()
        sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run()
    }.unit

  override def implementationCases(env: EdgeIdentityRepositorySpec.Env) =
    List(
      test("the migration leaves a first-party default group for the edges that existed") {
        for default <- env.groups.find(EdgeGroup.DefaultId)
        yield assertTrue(default == Some(EdgeGroupRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)))
      },
    )
