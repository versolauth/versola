package versola.util.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/** What any [[RevocationRepository]] is held to: pagination, cursor resumption, expiry filtering,
  * and the widen-on-re-revoke semantics the cache depends on. A storage module extends this and
  * supplies the wiring: see `PostgresRevocationRepositorySpec`.
  *
  * What is deliberately left out of here: whether a given query plan uses an index. That depends
  * on a particular database's optimizer, so it belongs to the storage-specific cases.
  */
trait RevocationRepositorySpec extends DatabaseSpecBase[RevocationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Truncated to microseconds: the least precision any SQL `timestamp` column this suite might
  // run against is guaranteed to keep. An `Instant` compared against one that has round-tripped
  // through such a column needs to be truncated the same way, or the two never compare equal.
  private def farFuture = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

  private def jti(id: String) = RevocationKey.Jti(id)

  private def fam(id: String) = RevocationKey.Fam(id)

  private def sub(id: String) = RevocationKey.Sub(id)

  private def revoke(env: RevocationRepositorySpec.Env, revocation: Revocation) =
    env.revoke(revocation)

  override def testCases(env: RevocationRepositorySpec.Env) =
    List(
      test("returns nothing when the table is empty") {
        for page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.isEmpty, page.last.isEmpty, !page.hasMore)
      } @@ TestAspect.withLiveClock,
      test("orders by (revoked_at, revoked_key) and resumes exactly where a cursor leaves off") {
        for
          _ <- revoke(env, Revocation(jti("a"), farFuture, None))
          _ <- revoke(env, Revocation(jti("b"), farFuture, None))
          _ <- revoke(env, Revocation(jti("c"), farFuture, None))
          first <- env.repository.activeSince(RevocationCursor.Beginning, limit = 2)
          second <- env.repository.activeSince(first.last.get, limit = 2)
        yield assertTrue(
          first.revocations.map(_.key) == List(jti("a"), jti("b")),
          first.hasMore,
          second.revocations.map(_.key) == List(jti("c")),
          !second.hasMore,
        )
      } @@ TestAspect.withLiveClock,
      // Every kind of key shares one text column, so a kind the reader cannot decode back is a
      // row it silently drops: an entry that revokes nothing, on a list whose whole job is to
      // revoke.
      test("round-trips every kind of key through the durable list") {
        val keys = List(
          fam("family-1"),
          RevocationKey.Sid("session-1"),
          sub("user-1"),
          jti("token-1"),
        )
        for
          _ <- ZIO.foreachDiscard(keys)(key => revoke(env, Revocation(key, farFuture, None)))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.key).toSet == keys.toSet)
      } @@ TestAspect.withLiveClock,
      test("excludes rows already expired") {
        for
          _ <- revoke(env, Revocation(jti("expired"), Instant.now().minusSeconds(60), None))
          _ <- revoke(env, Revocation(jti("live"), farFuture, None))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.key) == List(jti("live")))
      } @@ TestAspect.withLiveClock,
      // hasMore is a heuristic, not a lookahead: a page that exactly fills the limit reports more
      // regardless of whether any row is actually left, because the alternative is a second query
      // per page just to answer a question the next read answers for free.
      test("hasMore is true when a page exactly fills the limit, even with nothing left after it") {
        for
          _ <- revoke(env, Revocation(jti("only"), farFuture, None))
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
          _ <- revoke(env, Revocation(jti("a"), Instant.now().plusSeconds(10), None))
          _ <- revoke(env, Revocation(jti("a"), widened, None))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.key) == List(jti("a")), page.revocations.head.expiresAt == widened)
      } @@ TestAspect.withLiveClock,
      test("re-revoking never narrows the window") {
        val kept = farFuture
        for
          _ <- revoke(env, Revocation(jti("a"), kept, None))
          _ <- revoke(env, Revocation(jti("a"), Instant.now().plusSeconds(10), None))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(page.revocations.map(_.expiresAt) == List(kept))
      } @@ TestAspect.withLiveClock,
      test("re-revoking moves issuedBefore forward even when the new expiry is shorter") {
        // `expires_at` is derived from whatever the writing replica believes the widest access
        // token TTL to be, so a second invalidation can carry a shorter one. Guarding the write on
        // expiry alone would skip it, and the tokens issued between the two invalidations would
        // stay accepted.
        val firstAction = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val secondAction = firstAction.plusSeconds(60)
        for
          _ <- revoke(env, Revocation(sub("u"), firstAction.plusSeconds(3600), Some(firstAction)))
          _ <- revoke(env, Revocation(sub("u"), secondAction.plusSeconds(300), Some(secondAction)))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
        yield assertTrue(
          page.revocations.map(_.issuedBefore) == List(Some(secondAction)),
          // Neither field is narrowed: the shorter expiry does not pull the entry in.
          page.revocations.map(_.expiresAt) == List(firstAction.plusSeconds(3600)),
        )
      } @@ TestAspect.withLiveClock,
    )

object RevocationRepositorySpec:
  /** @param revoke writes through the storage's own writer, which is not part of the shared trait: each
    *               service writes its table in its own way.
    */
  case class Env(repository: RevocationRepository, revoke: Revocation => Task[Unit])
