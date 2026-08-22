package versola.edge.revocation

import org.scalamock.stubs.ZIOStubs
import versola.edge.model.{AccessTokenId, SessionId}
import versola.edge.{EdgeSettings, EdgeSettingsSyncClient}
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.time.Instant

object TokenRevocationServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val jti = RevocationKey.Jti(AccessTokenId("token-1"))
  private val sid = RevocationKey.Sid(SessionId("session-1"))
  private val sub = RevocationKey.Sub("user-1")
  private val otherJti = RevocationKey.Jti(AccessTokenId("token-2"))

  private val farFuture = Instant.EPOCH.plusSeconds(3600)

  /** When the token under test was issued. Only entries carrying an `issuedBefore` care. */
  private val issuedAt = Instant.EPOCH

  private def notificationsOf(revocations: Revocation*): RevocationNotifications =
    new RevocationNotifications:
      override def notifications = ZStream.fromIterable(revocations)

  def spec = suite("TokenRevocationService")(
    test("answers from memory once the list is loaded, without touching the database") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture)))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti), issuedAt)
        live <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        revoked,
        !live,
        // One call for the load itself: neither lookup needed the database.
        repository.listActive.calls.size == 1,
        repository.find.calls.isEmpty,
      )
    },
    test("stops honouring an entry once the token it names would have expired anyway") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- service.reload
        beforeExpiry <- service.isRevoked(List(jti), issuedAt)
        _ <- TestClock.adjust(61.seconds)
        afterExpiry <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(beforeExpiry, !afterExpiry)
    },
    test("reports a token revoked when any of its keys is listed, including its session") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(sid, farFuture)))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti, sid), issuedAt)
      yield assertTrue(revoked)
    },
    test("verifies a miss against the database once the list no longer fits in the cache") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 1)
      for
        // Two active revocations against a cache that holds one: the cache can no longer
        // prove that a key it does not hold was never revoked.
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture), Revocation(sid, farFuture)))
        _ <- repository.find.succeedsWith(Some(Revocation(otherJti, farFuture)))
        _ <- service.reload
        revoked <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(revoked, repository.find.calls == List(otherJti))
    },
    test("stops trusting a miss once the cache has had to evict an entry") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 1)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture)))
        _ <- repository.find.succeedsWith(None)
        _ <- service.reload
        beforeOverflow <- service.isRevoked(List(otherJti), issuedAt)
        // A second live revocation does not fit alongside the first, so the cache evicts
        // one of them and can no longer answer for keys it does not hold.
        _ <- service.consume(notificationsOf(Revocation(sid, farFuture)))
        _ <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        !beforeOverflow,
        repository.find.calls == List(otherJti),
      )
    },
    test("rejects the token when the database cannot answer a lookup it has to make") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 1)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture), Revocation(sid, farFuture)))
        _ <- repository.find.failsWith(RuntimeException("connection refused"))
        _ <- service.reload
        revoked <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(revoked)
    },
    test("falls back to per-miss lookups when the list cannot be loaded at all") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.failsWith(RuntimeException("connection refused"))
        _ <- repository.find.succeedsWith(None)
        _ <- service.reload
        revoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!revoked, repository.find.calls == List(jti))
    },
    test("rejects a token from the moment it is revoked, without waiting to hear about it") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(Nil)
        _ <- repository.revokeAll.succeedsWith(())
        _ <- service.reload
        // No notification is delivered here: the replica that wrote the revocation must
        // already honour it.
        _ <- service.revoke(List(Revocation(jti, farFuture)))
        revoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(revoked, repository.find.calls.isEmpty)
    },
    test("applies revocations announced by another replica") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(Nil)
        _ <- service.reload
        before <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(notificationsOf(Revocation(jti, farFuture)))
        after <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after, repository.find.calls.isEmpty)
    },
    test("growing the cache central holds for this edge restores the in-memory-only path") {
      val repository = stub[RevocationRepository]
      val settingsClient = stub[EdgeSettingsSyncClient]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 1)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture), Revocation(sid, farFuture)))
        _ <- repository.find.succeedsWith(None)
        _ <- service.reload
        // Two active revocations against a cache that holds one.
        beforeResize <- service.isRevoked(List(otherJti), issuedAt)
        _ <- settingsClient.get.succeedsWith(EdgeSettings(revocationCacheSize = 10))
        _ <- service.applySettings(settingsClient)
        _ <- service.isRevoked(List(otherJti), issuedAt)
        bothStillRevoked <- service.isRevoked(List(jti), issuedAt).zipWith(service.isRevoked(List(sid), issuedAt))(_ && _)
      yield assertTrue(
        !beforeResize,
        // The lookup before the resize had to be verified against the database; the one
        // after it did not, because the whole list now fits.
        repository.find.calls == List(otherJti),
        bothStillRevoked,
      )
    },
    test("shrinking the cache below the active list stops a miss being trusted") {
      val repository = stub[RevocationRepository]
      val settingsClient = stub[EdgeSettingsSyncClient]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture), Revocation(sid, farFuture)))
        _ <- repository.find.succeedsWith(None)
        _ <- service.reload
        beforeResize <- service.isRevoked(List(otherJti), issuedAt)
        _ <- settingsClient.get.succeedsWith(EdgeSettings(revocationCacheSize = 1))
        _ <- service.applySettings(settingsClient)
        _ <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        !beforeResize,
        repository.find.calls == List(otherJti),
      )
    },
    test("keeps the size it has when central cannot be reached") {
      val repository = stub[RevocationRepository]
      val settingsClient = stub[EdgeSettingsSyncClient]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      for
        _ <- repository.listActive.succeedsWith(List(Revocation(jti, farFuture)))
        _ <- service.reload
        _ <- settingsClient.get.failsWith(RuntimeException("connection refused"))
        _ <- service.applySettings(settingsClient)
        revoked <- service.isRevoked(List(jti), issuedAt)
        live <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(revoked, !live, repository.find.calls.isEmpty)
    },
    test("a user-wide revocation rejects the tokens that user already held") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        _ <- repository.listActive.succeedsWith(List(
          Revocation(sub, revokedAt.plusSeconds(600), issuedBefore = Some(revokedAt)),
        ))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.minusSeconds(60))
      yield assertTrue(revoked)
    },
    test("a user-wide revocation leaves alone a token issued after it") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        _ <- repository.listActive.succeedsWith(List(
          Revocation(sub, revokedAt.plusSeconds(600), issuedBefore = Some(revokedAt)),
        ))
        _ <- service.reload
        // The user logged in again after the administrator ended their sessions. The entry
        // is still live, and must not lock them out of the session they just started.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.plusSeconds(1))
      yield assertTrue(!revoked)
    },
    test("a user-wide revocation covers a token issued in the same second as itself") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 10)
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        _ <- repository.listActive.succeedsWith(List(
          Revocation(sub, revokedAt.plusSeconds(600), issuedBefore = Some(revokedAt)),
        ))
        _ <- service.reload
        // `iat` is whole seconds, so the two cannot be ordered. Sparing the token would let
        // one the administrator meant to end through; the tie goes to the revocation.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt)
      yield assertTrue(revoked)
    },
    test("reloading drops expired entries and restores the in-memory-only path") {
      val repository = stub[RevocationRepository]
      val service = TokenRevocationService.Impl(repository, initialMaxSize = 1)
      for
        _ <- repository.listActive.returnsZIO: _ =>
          Clock.instant.map: now =>
            // Only one entry is still active on the second load, so the whole list fits again.
            List(Revocation(jti, Instant.EPOCH.plusSeconds(60)), Revocation(sid, farFuture))
              .filter(_.expiresAt.isAfter(now))
        _ <- repository.find.succeedsWith(None)
        _ <- service.reload
        incomplete <- service.isRevoked(List(otherJti), issuedAt)
        _ <- TestClock.adjust(61.seconds)
        _ <- service.reload
        _ <- service.isRevoked(List(otherJti), issuedAt)
        expired <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(
        // Before the reload the miss had to be verified; after it, it did not.
        repository.find.calls == List(otherJti),
        !incomplete,
        !expired,
      )
    },
  )
