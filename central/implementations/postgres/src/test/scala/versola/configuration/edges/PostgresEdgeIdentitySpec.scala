package versola.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.*
import versola.util.postgres.PostgresSpec
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** The tables behind edge groups and enrollment: that the one-time token really is spent once, and that an edge
  * cannot be in a group that is not there. */
object PostgresEdgeIdentitySpec extends PostgresSpec:

  private val edgeId = EdgeId("test-edge")
  private val otherEdgeId = EdgeId("other-edge")
  private val partner = EdgeGroupRecord(EdgeGroupId("partner-acme"), EdgeGroupKind.Partner)
  private val jwk = Json.Obj("kty" -> Json.Str("RSA"), "n" -> Json.Str("n"), "e" -> Json.Str("AQAB"))
  private val now = Instant.parse("2026-10-21T10:00:00Z")

  private def reset =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run()
        sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run()
    .unit

  private def repositories =
    ZIO.service[TransactorZIO].map: xa =>
      (
        PostgresEdgeRepository(xa),
        PostgresEdgeGroupRepository(xa),
        PostgresEdgeEnrollmentTokenRepository(xa),
        PostgresEdgeCertificateRepository(xa),
        PostgresEdgeIdentityChainRepository(xa),
      )

  def spec = suite("edge groups and enrollment tables")(
    test("the migration leaves a first-party default group for the edges that existed") {
      for
        _ <- reset
        (_, groups, _, _, _) <- repositories
        default <- groups.find(EdgeGroup.DefaultId)
      yield assertTrue(default == Some(EdgeGroupRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)))
    },
    test("a group is created once and keeps the kind it was created with") {
      for
        _ <- reset
        (_, groups, _, _, _) <- repositories
        first <- groups.create(partner)
        second <- groups.create(partner.copy(kind = EdgeGroupKind.FirstParty))
        found <- groups.find(partner.id)
        all <- groups.getAll
      yield assertTrue(first, !second, found == Some(partner), all.map(_.id).toSet == Set(EdgeGroup.DefaultId, partner.id))
    },
    test("an edge carries its group and the group's kind") {
      for
        _ <- reset
        (edges, groups, _, _, _) <- repositories
        _ <- groups.create(partner)
        _ <- edges.createEdge(edgeId, jwk, true, partner.id)
        _ <- edges.createEdge(otherEdgeId, jwk, true, EdgeGroup.DefaultId)
        mine <- edges.find(edgeId)
        other <- edges.find(otherEdgeId)
        all <- edges.getAll
      yield assertTrue(
        mine.map(e => (e.groupId, e.groupKind)) == Some((partner.id, EdgeGroupKind.Partner)),
        other.map(e => (e.groupId, e.groupKind)) == Some((EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)),
        all.size == 2,
      )
    },
    test("an edge cannot be created in a group that does not exist") {
      for
        _ <- reset
        (edges, _, _, _, _) <- repositories
        failure <- edges.createEdge(edgeId, jwk, true, EdgeGroupId("nobody")).flip
      yield assertTrue(failure.getMessage.contains("edge_groups") || failure.getMessage.contains("violates foreign key"))
    },
    test("a token is spent once, and only for the edge it was created for") {
      for
        _ <- reset
        (edges, _, tokens, _, _) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- tokens.create("hash-1", edgeId, now.plusSeconds(3600))
        first <- tokens.consume("hash-1", now)
        second <- tokens.consume("hash-1", now)
      yield assertTrue(first == Some(edgeId), second.isEmpty)
    },
    test("an expired or unknown token is not spent") {
      for
        _ <- reset
        (edges, _, tokens, _, _) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- tokens.create("hash-1", edgeId, now.plusSeconds(10))
        expired <- tokens.consume("hash-1", now.plusSeconds(10))
        unknown <- tokens.consume("hash-2", now)
        stillThere <- tokens.consume("hash-1", now)
      yield assertTrue(expired.isEmpty, unknown.isEmpty, stillThere == Some(edgeId))
    },
    test("looking at a token spends nothing, and sees only a token that is still good") {
      for
        _ <- reset
        (edges, _, tokens, _, _) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- tokens.create("hash-1", edgeId, now.plusSeconds(10))
        first <- tokens.peek("hash-1", now)
        again <- tokens.peek("hash-1", now)
        unknown <- tokens.peek("hash-2", now)
        expired <- tokens.peek("hash-1", now.plusSeconds(10))
        spent <- tokens.consume("hash-1", now)
        afterSpent <- tokens.peek("hash-1", now)
      yield assertTrue(first == Some(edgeId), again == Some(edgeId), unknown.isEmpty, expired.isEmpty, spent == Some(edgeId), afterSpent.isEmpty)
    },
    test("the chain of an edge begins, is moved, is listed, and goes with the edge") {
      for
        _ <- reset
        (edges, _, _, _, chains) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- edges.createEdge(otherEdgeId, jwk, true, EdgeGroup.DefaultId)
        none <- chains.startedAt(edgeId)
        _ <- chains.start(edgeId, now)
        _ <- chains.start(otherEdgeId, now.plusSeconds(10))
        first <- chains.startedAt(edgeId)
        _ <- chains.start(edgeId, now.plusSeconds(500))
        moved <- chains.startedAt(edgeId)
        everyone <- chains.all
        _ <- edges.deleteEdge(edgeId)
        gone <- chains.startedAt(edgeId)
      yield assertTrue(
        none.isEmpty,
        first == Some(now),
        moved == Some(now.plusSeconds(500)),
        everyone == Map(edgeId -> now.plusSeconds(500), otherEdgeId -> now.plusSeconds(10)),
        gone.isEmpty,
      )
    },
    test("two simultaneous trades of one token enrol exactly one") {
      for
        _ <- reset
        (edges, _, tokens, _, _) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- tokens.create("hash-1", edgeId, now.plusSeconds(3600))
        results <- ZIO.foreachPar(1 to 8)(_ => tokens.consume("hash-1", now))
      yield assertTrue(results.count(_.isDefined) == 1)
    },
    test("tokens and certificates go with the edge when it is deleted") {
      for
        _ <- reset
        (edges, _, tokens, certificates, _) <- repositories
        _ <- edges.createEdge(edgeId, jwk, true, EdgeGroup.DefaultId)
        _ <- tokens.create("hash-1", edgeId, now.plusSeconds(3600))
        _ <- certificates.record(edgeId, "ab12", now.plusSeconds(86400), now)
        _ <- certificates.record(edgeId, "ab12", now.plusSeconds(86400), now)
        _ <- certificates.record(edgeId, "cd34", now.plusSeconds(172800), now.plusSeconds(3600))
        _ <- edges.deleteEdge(edgeId)
        spent <- tokens.consume("hash-1", now)
      yield assertTrue(spent.isEmpty)
    },
  ) @@ TestAspect.sequential
