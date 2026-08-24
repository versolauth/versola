package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.AccessTokenId
import versola.util.DatabaseSpecBase
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Conformance suite for any [[RevocationRepository]] implementation: pagination, cursor
  * resumption, expiry filtering, and the widen-on-re-revoke semantics `isRevoked` depends on.
  * A backend module extends this and supplies the wiring — see `PostgresRevocationRepositorySpec`
  * in `edge-postgres-impl` for the one binding that exists today.
  *
  * What is deliberately left out of here: whether a given query plan uses an index. That
  * depends on a particular database's optimizer, so it belongs to the backend-specific suite
  * instead.
  */
trait RevocationRepositorySpec extends DatabaseSpecBase[RevocationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Truncated to microseconds: the least precision any SQL `timestamp` column this suite
  // might run against is guaranteed to keep. An `Instant` compared against one that has
  // round-tripped through such a column needs to be truncated the same way, or the two
  // never compare equal on a backend that doesn't keep nanoseconds.
  private def farFuture = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

  private def jti(id: String) = RevocationKey.Jti(AccessTokenId(id))

  override def testCases(env: RevocationRepositorySpec.Env) =
    List(
      test("returns nothing when the table is empty") {
        for page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.isEmpty, page.last.isEmpty, !page.hasMore)
      } @@ TestAspect.withLiveClock,
      test("orders by (revoked_at, revoked_key) and resumes exactly where a cursor leaves off") {
        for
          _ <- env.repository.revokeAll(List(Revocation(jti("a"), farFuture, None)))
          _ <- env.repository.revokeAll(List(Revocation(jti("b"), farFuture, None)))
          _ <- env.repository.revokeAll(List(Revocation(jti("c"), farFuture, None)))
          first <- env.repository.activeSince(RevocationCursor.Beginning, limit = 2)
          second <- env.repository.activeSince(first.last.get, limit = 2)
        yield assertTrue(
          first.revocations.map(_.key) == List(jti("a"), jti("b")),
          first.hasMore,
          second.revocations.map(_.key) == List(jti("c")),
          !second.hasMore,
        )
      } @@ TestAspect.withLiveClock,
      test("excludes rows already expired") {
        for
          _ <- env.repository.revokeAll(List(Revocation(jti("expired"), Instant.now().minusSeconds(60), None)))
          _ <- env.repository.revokeAll(List(Revocation(jti("live"), farFuture, None)))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.key) == List(jti("live")))
      } @@ TestAspect.withLiveClock,
      // hasMore is a heuristic, not a lookahead: a page that exactly fills the limit reports
      // more regardless of whether any row is actually left, because the alternative is a
      // second query per page just to answer a question the next read answers for free.
      test("hasMore is true when a page exactly fills the limit, even with nothing left after it") {
        for
          _ <- env.repository.revokeAll(List(Revocation(jti("only"), farFuture, None)))
          full <- env.repository.activeSince(RevocationCursor.Beginning, limit = 1)
          next <- env.repository.activeSince(full.last.get, limit = 1)
        yield assertTrue(
          full.revocations.map(_.key) == List(jti("only")),
          full.hasMore,
          next.revocations.isEmpty,
          !next.hasMore,
        )
      } @@ TestAspect.withLiveClock,
      test("re-revoking widens the window rather than being skipped as a duplicate") {
        val widened = farFuture
        for
          _ <- env.repository.revokeAll(List(Revocation(jti("a"), Instant.now().plusSeconds(10), None)))
          _ <- env.repository.revokeAll(List(Revocation(jti("a"), widened, None)))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.key) == List(jti("a")), page.revocations.head.expiresAt == widened)
      } @@ TestAspect.withLiveClock,
    )

object RevocationRepositorySpec:
  case class Env(repository: RevocationRepository)
