package versola.edge.revocation

import versola.edge.model.AccessTokenId
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Test-only escape hatch to clear whatever state a [[RevocationRepository]] implementation
  * holds, between tests. Not part of the production interface: no production caller ever
  * needs to delete everything, only test isolation does, so it stays out of the trait every
  * real caller depends on.
  */
trait RevocationRepositoryTestSupport:
  def reset: Task[Unit]

/** Conformance suite for any [[RevocationRepository]] implementation: pagination, cursor
  * resumption, expiry filtering, and the widen-on-re-revoke semantics `isRevoked` depends on.
  * Nothing here assumes SQL, an index, or a particular database — a backend module extends
  * this and supplies the wiring (see `PostgresRevocationRepositorySpec` in `edge-postgres-impl`
  * for the one binding that exists today).
  *
  * What is deliberately left out of here: whether a given query plan uses an index. That is
  * exactly the kind of thing that cannot be abstracted over a trait — it depends on that
  * database's optimizer — and belongs to the backend-specific suite instead.
  */
abstract class RevocationRepositoryContractSpec extends ZIOSpecDefault:

  /** Self-contained: whatever a backend needs to acquire its own connections lives inside
    * this layer (as [[versola.util.postgres.PostgresSpec.transactor]] does for Postgres), so
    * this suite itself never has to know what that is.
    */
  def repositoryLayer: ZLayer[Any, Throwable, RevocationRepository & RevocationRepositoryTestSupport]

  // Truncated to microseconds: the least precision any SQL `timestamp` column this suite
  // might run against is guaranteed to keep. An `Instant` compared against one that has
  // round-tripped through such a column needs to be truncated the same way, or the two
  // never compare equal on a backend that doesn't keep nanoseconds.
  private def farFuture = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

  private def jti(id: String) = RevocationKey.Jti(AccessTokenId(id))

  private def reset = ZIO.serviceWithZIO[RevocationRepositoryTestSupport](_.reset)

  def spec = (suite("RevocationRepository")(
    test("returns nothing when the table is empty") {
      for
        repo <- ZIO.service[RevocationRepository]
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.isEmpty, page.last.isEmpty, !page.hasMore)
    },
    test("orders by (revoked_at, revoked_key) and resumes exactly where a cursor leaves off") {
      for
        repo <- ZIO.service[RevocationRepository]
        _ <- repo.revokeAll(List(Revocation(jti("a"), farFuture, None)))
        _ <- repo.revokeAll(List(Revocation(jti("b"), farFuture, None)))
        _ <- repo.revokeAll(List(Revocation(jti("c"), farFuture, None)))
        first <- repo.activeSince(RevocationCursor.Beginning, limit = 2)
        second <- repo.activeSince(first.last.get, limit = 2)
      yield assertTrue(
        first.revocations.map(_.key) == List(jti("a"), jti("b")),
        first.hasMore,
        second.revocations.map(_.key) == List(jti("c")),
        !second.hasMore,
      )
    },
    test("excludes rows already expired") {
      for
        repo <- ZIO.service[RevocationRepository]
        _ <- repo.revokeAll(List(Revocation(jti("expired"), Instant.now().minusSeconds(60), None)))
        _ <- repo.revokeAll(List(Revocation(jti("live"), farFuture, None)))
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.map(_.key) == List(jti("live")))
    },
    // hasMore is a heuristic, not a lookahead: a page that exactly fills the limit reports
    // more regardless of whether any row is actually left, because the alternative is a
    // second query per page just to answer a question the next read answers for free.
    test("hasMore is true when a page exactly fills the limit, even with nothing left after it") {
      for
        repo <- ZIO.service[RevocationRepository]
        _ <- repo.revokeAll(List(Revocation(jti("only"), farFuture, None)))
        full <- repo.activeSince(RevocationCursor.Beginning, limit = 1)
        next <- repo.activeSince(full.last.get, limit = 1)
      yield assertTrue(
        full.revocations.map(_.key) == List(jti("only")),
        full.hasMore,
        next.revocations.isEmpty,
        !next.hasMore,
      )
    },
    test("re-revoking widens the window rather than being skipped as a duplicate") {
      val widened = farFuture
      for
        repo <- ZIO.service[RevocationRepository]
        _ <- repo.revokeAll(List(Revocation(jti("a"), Instant.now().plusSeconds(10), None)))
        _ <- repo.revokeAll(List(Revocation(jti("a"), widened, None)))
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.map(_.key) == List(jti("a")), page.revocations.head.expiresAt == widened)
    },
  ) @@ TestAspect.before(reset) @@ TestAspect.after(reset) @@ TestAspect.withLiveClock @@ TestAspect.sequential)
    .provideLayer(repositoryLayer)
