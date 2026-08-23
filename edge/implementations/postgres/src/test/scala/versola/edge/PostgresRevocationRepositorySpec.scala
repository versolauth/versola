package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.model.AccessTokenId
import versola.edge.revocation.{Revocation, RevocationCursor, RevocationKey}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.temporal.ChronoUnit
import java.time.Instant

/** Exercises `activeSince` and its index against a real database: pagination, resuming from
  * a cursor, and the row-comparison predicate are all things a stub can't get wrong the way
  * a live query can.
  */
object PostgresRevocationRepositorySpec extends PostgresSpec:

  private def repository = ZIO.serviceWithZIO[TransactorZIO](xa => ZIO.succeed(PostgresRevocationRepository(xa)))

  private def clean = ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"DELETE FROM revocations".update.run()))

  // Truncated to microseconds: `timestamptz` doesn't hold nanosecond precision, so an
  // `Instant` compared against one that has round-tripped through the column needs to be
  // truncated the same way the column would have, or the two never compare equal.
  private def farFuture = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

  private def jti(id: String) = RevocationKey.Jti(AccessTokenId(id))

  def spec = suite("PostgresRevocationRepository")(
    test("returns nothing when the table is empty") {
      for
        repo <- repository
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.isEmpty, page.last.isEmpty, !page.hasMore)
    },
    test("orders by (revoked_at, revoked_key) and resumes exactly where a cursor leaves off") {
      for
        repo <- repository
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
        repo <- repository
        _ <- repo.revokeAll(List(Revocation(jti("expired"), Instant.now().minusSeconds(60), None)))
        _ <- repo.revokeAll(List(Revocation(jti("live"), farFuture, None)))
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.map(_.key) == List(jti("live")))
    },
    // `hasMore` is a heuristic, not a lookahead: a page that exactly fills the limit reports
    // more regardless of whether any row is actually left, because the alternative is a
    // second query per page just to answer a question the next read answers for free.
    test("hasMore is true when a page exactly fills the limit, even with nothing left after it") {
      for
        repo <- repository
        _ <- repo.revokeAll(List(Revocation(jti("only"), farFuture, None)))
        full <- repo.activeSince(RevocationCursor.Beginning, limit = 1)
        next <- repo.activeSince(full.last.get, limit = 1)
      yield assertTrue(full.revocations.map(_.key) == List(jti("only")), full.hasMore, next.revocations.isEmpty, !next.hasMore)
    },
    test("re-revoking widens the window rather than being skipped as a duplicate") {
      val widened = farFuture
      for
        repo <- repository
        _ <- repo.revokeAll(List(Revocation(jti("a"), Instant.now().plusSeconds(10), None)))
        _ <- repo.revokeAll(List(Revocation(jti("a"), widened, None)))
        page <- repo.activeSince(RevocationCursor.Beginning, limit = 10)
      yield assertTrue(page.revocations.map(_.key) == List(jti("a")), page.revocations.head.expiresAt == widened)
    },
  ) @@ TestAspect.before(clean) @@ TestAspect.after(clean) @@ TestAspect.withLiveClock @@ TestAspect.sequential
