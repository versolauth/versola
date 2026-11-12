package versola.configuration.partners

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind}
import versola.central.configuration.edges.{EdgeId, EdgeRepository}
import versola.central.configuration.partners.PartnerRepositorySpec
import versola.configuration.edges.PostgresEdgeRepository
import versola.util.postgres.PostgresSpec
import zio.*
import zio.json.ast.Json
import zio.test.*

object PostgresPartnerRepositorySpec extends PostgresSpec, PartnerRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PartnerRepositorySpec.Env(
        PostgresPartnerRepository(xa),
        new PartnerRepositorySpec.Fixtures:
          def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit] =
            xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($id, ${kind.code})".update.run()).unit,
      )

  override def beforeEach(env: PartnerRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partners CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A') ON CONFLICT (id) DO NOTHING".update.run())
    }.unit

  /** #962 review: the number of edges a partner may run is the table's to enforce, whatever is created at once. */
  override def implementationCases(env: PartnerRepositorySpec.Env) =
    List(
      test("a partner's edges are as many as its limit and no more, however many are enrolled at once; another group has none") {
        val acme = partner("acme", groupA).copy(limits = limits.copy(maxEdges = 2))
        val jwk = Json.Obj("kty" -> Json.Str("RSA"), "n" -> Json.Str("n"), "e" -> Json.Str("AQAB"))
        for
          xa <- ZIO.service[TransactorZIO]
          edges = PostgresEdgeRepository(xa)
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.fixtures.group(groupB, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          results <- ZIO.foreachPar(1 to 8)(n => edges.createEdge(EdgeId(s"acme-edge-$n"), Some(jwk), true, groupA, None).either)
          stored <- xa.connect(sql"SELECT COUNT(*) FROM edges WHERE group_id = $groupA".query[Long].run().head)
          // a group no partner points at has no limit
          free <- ZIO.foreach(1 to 4)(n => edges.createEdge(EdgeId(s"free-edge-$n"), Some(jwk), true, groupB, None).either)
          _ <- xa.connect(sql"TRUNCATE TABLE edges CASCADE".update.run())
        yield assertTrue(
          results.count(_.isRight) == 2,
          results.collect { case Left(error) => error.getMessage }.forall(_.contains("partner_edge_limit")),
          stored == 2L,
          free.forall(_.isRight),
        )
      },
    )
