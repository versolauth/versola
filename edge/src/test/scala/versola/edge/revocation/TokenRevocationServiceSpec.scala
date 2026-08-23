package versola.edge.revocation

import org.scalamock.stubs.ZIOStubs
import versola.edge.model.{AccessTokenId, SessionId}
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

  private def revocation(key: RevocationKey, expiresAt: Instant = farFuture): Revocation =
    Revocation(key, expiresAt, issuedBefore = None)

  private def userRevocation(revokedAt: Instant): Revocation =
    Revocation(sub, revokedAt.plusSeconds(600), issuedBefore = Some(revokedAt))

  private def notificationsOf(revocations: Revocation*): RevocationNotifications =
    eventsOf(revocations.map(RevocationEvent.Revoked(_))*)

  private def eventsOf(events: RevocationEvent*): RevocationNotifications =
    new RevocationNotifications:
      override def notifications = ZStream.fromIterable(events)

  def spec = suite("TokenRevocationService")(
    test("answers from memory, never from the database") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(jti)))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti), issuedAt)
        live <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        revoked,
        !live,
        // One call, made by the load itself. Neither answer cost anything beyond it, and a
        // miss cost no more than a hit: what an unrevoked token pays is not a caller's to
        // influence by presenting one that misses.
        repository.listActive.calls.size == 1,
      )
    },
    test("stops honouring an entry once the token it names would have expired anyway") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- service.reload
        beforeExpiry <- service.isRevoked(List(jti), issuedAt)
        _ <- TestClock.adjust(61.seconds)
        afterExpiry <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(beforeExpiry, !afterExpiry)
    },
    test("reports a token revoked when any of its keys is listed, including its session") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(sid)))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti, sid), issuedAt)
      yield assertTrue(revoked)
    },
    test("rejects a token from the moment it is revoked, without waiting to hear about it") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(Nil)
        _ <- repository.revokeAll.succeedsWith(())
        _ <- service.reload
        // No notification is delivered here: the replica that wrote the revocation must
        // already honour it.
        _ <- service.revoke(List(revocation(jti)))
        revoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(revoked)
    },
    test("applies revocations announced by another replica") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(Nil)
        _ <- service.reload
        before <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(notificationsOf(revocation(jti)))
        after <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("rebuilds the list when the notification feed reconnects") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(jti)))
        // Written while the feed was down, so its notification was never delivered: only a
        // reload can find it, which is what the reconnect has to trigger.
        before <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(eventsOf(RevocationEvent.Resubscribed))
        after <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("keeps a revocation that arrived while a reload was in flight") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.revokeAll.succeedsWith(())
        // The revocation this replica has just written is not in what the database returns:
        // a reload merges into what it holds rather than replacing it, because replacing
        // would drop every revocation newer than the query.
        _ <- repository.listActive.succeedsWith(List(revocation(sid)))
        _ <- service.revoke(List(revocation(jti)))
        _ <- service.reload
        written <- service.isRevoked(List(jti), issuedAt)
        loaded <- service.isRevoked(List(sid), issuedAt)
      yield assertTrue(written, loaded)
    },
    test("drops expired entries on reload even when the database cannot be reached") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- service.reload
        _ <- TestClock.adjust(61.seconds)
        // Reclaiming what has expired is the only thing bounding what this replica holds,
        // so it cannot be something an unreachable database prevents.
        _ <- repository.listActive.failsWith(RuntimeException("connection refused"))
        _ <- service.reload
        held <- service.entryCount
      yield assertTrue(held == 0)
    },
    test("keeps serving the list it has when a reload fails") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(revocation(jti)))
        _ <- service.reload
        _ <- repository.listActive.failsWith(RuntimeException("connection refused"))
        _ <- service.reload
        // A replica that cannot reach the database can be missing revocations written since
        // it last read, but is never wrong about the ones it already holds.
        stillRevoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(stillRevoked)
    },
    test("a user-wide revocation rejects the tokens that user already held") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(userRevocation(revokedAt)))
        _ <- service.reload
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.minusSeconds(60))
      yield assertTrue(revoked)
    },
    test("a user-wide revocation leaves alone a token issued after it") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(userRevocation(revokedAt)))
        _ <- service.reload
        // The user logged in again after the administrator ended their sessions. The entry
        // is still live, and must not lock them out of the session they just started.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.plusSeconds(1))
      yield assertTrue(!revoked)
    },
    test("a user-wide revocation covers a token issued in the same second as itself") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository)
        _ <- repository.listActive.succeedsWith(List(userRevocation(revokedAt)))
        _ <- service.reload
        // `iat` is whole seconds, so the two cannot be ordered. Sparing the token would let
        // one the administrator meant to end through; the tie goes to the revocation.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt)
      yield assertTrue(revoked)
    },
  )
