package versola.central.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.Task
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** Conformance suite for the stores of edge enrollment tokens and certificates (with the edges they belong to), for any storage; see
  * `PostgresEdgeIdentityRepositorySpec` in `central-postgres-impl` for the one binding. */
trait EdgeIdentityRepositorySpec extends DatabaseSpecBase[EdgeIdentityRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val edgeId = EdgeId("test-edge")
  private val otherEdgeId = EdgeId("other-edge")
  private val sampleJwk = Json.Obj("kty" -> Json.Str("RSA"), "n" -> Json.Str("n"), "e" -> Json.Str("AQAB"))
  private val now = Instant.parse("2026-10-21T10:00:00Z")

  override def testCases(env: EdgeIdentityRepositorySpec.Env) =
    List(
      // the table has no CHECK on `kind`: the enum is what says which values exist, and a value it does not know is read as a failure, not
      // as one of the kinds
      test("a group stored with a kind that is none of the kinds fails to read, loudly, and the kinds there are read as they are") {
        val odd = EdgeGroupId("odd-group")
        val partner = EdgeGroupId("partner-group")
        for
          _ <- env.groups.create(EdgeGroupRecord(partner, EdgeGroupKind.Partner))
          _ <- env.raw.insert(odd, "reseller")
          known <- env.groups.find(partner)
          unknownOne <- env.groups.find(odd).either
          unknownAll <- env.groups.getAll.either
        yield assertTrue(
          known == Some(EdgeGroupRecord(partner, EdgeGroupKind.Partner)),
          unknownOne.left.exists(_.getMessage.contains("reseller")),
          unknownAll.left.exists(_.getMessage.contains("reseller")),
        )
      },
      test("revoking forgets the unspent tokens of one edge and counts them") {
        for
          _ <- env.edges.createEdge(edgeId, Some(sampleJwk), true, EdgeGroup.DefaultId, None)
          _ <- env.edges.createEdge(otherEdgeId, Some(sampleJwk), true, EdgeGroup.DefaultId, None)
          _ <- env.tokens.create("hash-1", edgeId, now.plusSeconds(3600))
          _ <- env.tokens.create("hash-2", edgeId, now.plusSeconds(3600))
          _ <- env.tokens.create("hash-3", otherEdgeId, now.plusSeconds(3600))
          spent <- env.tokens.consume("hash-2", now)
          revoked <- env.tokens.revoke(edgeId)
          first <- env.tokens.consume("hash-1", now)
          other <- env.tokens.consume("hash-3", now)
          again <- env.tokens.revoke(edgeId)
        yield assertTrue(spent == Some(edgeId), revoked == 1, first.isEmpty, other == Some(otherEdgeId), again == 0)
      },
      test("the latest certificate of each edge is the one issued last") {
        for
          _ <- env.edges.createEdge(edgeId, Some(sampleJwk), true, EdgeGroup.DefaultId, None)
          _ <- env.edges.createEdge(otherEdgeId, Some(sampleJwk), true, EdgeGroup.DefaultId, None)
          _ <- env.certificates.record(edgeId, "ab12", now.plusSeconds(86400), now)
          _ <- env.certificates.record(edgeId, "cd34", now.plusSeconds(172800), now.plusSeconds(3600))
          _ <- env.certificates.record(otherEdgeId, "ef56", now.plusSeconds(86400), now)
          latest <- env.certificates.latest
        yield assertTrue(
          latest.keySet == Set(edgeId, otherEdgeId),
          latest(edgeId).serial == "cd34",
          latest(otherEdgeId).serial == "ef56",
        )
      },
    )

object EdgeIdentityRepositorySpec:
  /** A group as a store may hold it that [[EdgeGroupRepository]] cannot create: with a kind that is no kind. Nothing in the table
    * limits what is written (the values are the Scala enum's), so a test needs a way to put one there. */
  trait RawGroups:
    def insert(id: EdgeGroupId, kind: String): Task[Unit]

  case class Env(
      edges: EdgeRepository,
      tokens: EdgeEnrollmentTokenRepository,
      certificates: EdgeCertificateRepository,
      groups: EdgeGroupRepository,
      raw: RawGroups,
  )
