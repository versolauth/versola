package versola.util.revocation

import org.scalamock.stubs.ZIOStubs
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.time.Instant

object RevocationCacheSpec extends ZIOSpecDefault, ZIOStubs:

  private val jti = RevocationKey.Jti("token-1")
  private val sid = RevocationKey.Sid("session-1")
  private val sub = RevocationKey.Sub("user-1")
  private val otherJti = RevocationKey.Jti("token-2")
  private val fam = RevocationKey.Fam("family-1")

  private val farFuture = Instant.EPOCH.plusSeconds(3600)

  /** When the token under test was issued. Only entries carrying an `issuedBefore` care. */
  private val issuedAt = Instant.EPOCH

  /** When the rows the repository hands back were written. Only the cursor cares. */
  private val writtenAt = Instant.EPOCH.plusSeconds(100)

  private def cursorOf(key: RevocationKey): RevocationCursor = RevocationCursor(writtenAt, key.encoded)

  /** Everything asked for, in one read: what the repository returns when the list fits inside a
    * single page.
    */
  private def onePage(revocations: Revocation*): RevocationPage =
    RevocationPage(revocations.toList, revocations.lastOption.map(r => cursorOf(r.key)), hasMore = false)

  /** A page that filled the limit it was given, so the reader has to come back for another. */
  private def fullPage(revocations: Revocation*): RevocationPage =
    onePage(revocations*).copy(hasMore = true)

  private def revocation(key: RevocationKey, expiresAt: Instant = farFuture): Revocation =
    Revocation(key, expiresAt, issuedBefore = None)

  private def userRevocation(revokedAt: Instant): Revocation =
    Revocation(sub, revokedAt.plusSeconds(600), issuedBefore = Some(revokedAt))

  private def notificationsOf(revocations: Revocation*): RevocationNotifications =
    eventsOf(revocations.map(RevocationEvent.Revoked(_))*)

  private def eventsOf(events: RevocationEvent*): RevocationNotifications =
    new RevocationNotifications:
      override def notifications = ZStream.fromIterable(events)

  def spec = suite("RevocationCache")(
    test("answers from memory, never from the database") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- cache.sync
        revoked <- cache.isRevoked(List(jti), issuedAt)
        live <- cache.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        revoked,
        !live,
        // One call, made by the load itself. Neither answer cost anything beyond it, and a miss
        // cost no more than a hit: what an unrevoked token pays is not a caller's to influence by
        // presenting one that misses.
        repository.activeSince.calls.size == 1,
      )
    },
    test("stops honouring an entry once the token it names would have expired anyway") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- cache.sync
        beforeExpiry <- cache.isRevoked(List(jti), issuedAt)
        _ <- TestClock.adjust(61.seconds)
        afterExpiry <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(beforeExpiry, !afterExpiry)
    },
    test("reports a token revoked when any of its keys is listed, including its session") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(sid)))
        _ <- cache.sync
        revoked <- cache.isRevoked(List(jti, sid), issuedAt)
      yield assertTrue(revoked)
    },
    test("a revoking replica learns of its own write the way every other one does") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- cache.sync
        // Nothing has told this replica the write landed yet: not a notification, not a
        // catch-up, so it answers no differently than one that never made the write.
        beforeNotified <- cache.isRevoked(List(jti), issuedAt)
        _ <- cache.consume(notificationsOf(revocation(jti)))
        afterNotified <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(!beforeNotified, afterNotified)
    },
    test("applies revocations announced by another replica") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- cache.sync
        before <- cache.isRevoked(List(jti), issuedAt)
        _ <- cache.consume(notificationsOf(revocation(jti)))
        after <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("catches up when the notification feed reconnects") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        // Written while the feed was down, so its notification was never delivered: only a reload
        // can find it, which is what the reconnect has to trigger.
        before <- cache.isRevoked(List(jti), issuedAt)
        _ <- cache.consume(eventsOf(RevocationEvent.Resubscribed))
        after <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("keeps a revocation that arrived by notification while a reload was in flight") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        // What the database returns does not include this one: a reload merges into what the
        // replica already holds rather than replacing it, because replacing would drop every
        // revocation newer than the query.
        _ <- repository.activeSince.succeedsWith(onePage(revocation(sid)))
        _ <- cache.consume(notificationsOf(revocation(jti)))
        _ <- cache.sync
        delivered <- cache.isRevoked(List(jti), issuedAt)
        loaded <- cache.isRevoked(List(sid), issuedAt)
      yield assertTrue(delivered, loaded)
    },
    test("a stale page cannot undo a wider revocation that landed while it was in flight") {
      val repository = stub[RevocationRepository]
      val firstInvalidation = Instant.EPOCH.plusSeconds(600)
      val secondInvalidation = firstInvalidation.plusSeconds(60)
      for
        cache <- RevocationCache.make(repository)
        // What the catch-up's query saw: the row as it stood before the user was invalidated a
        // second time.
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(firstInvalidation)))
        // The second invalidation widens the row in place, and its notification gets there first.
        // Applying the page afterwards must not put the earlier bound back.
        _ <- cache.consume(notificationsOf(userRevocation(secondInvalidation)))
        _ <- cache.sync
        // Issued between the two invalidations: covered by the second, not by the first.
        revoked <- cache.isRevoked(List(sub), issuedAt = firstInvalidation.plusSeconds(30))
      yield assertTrue(revoked)
    },
    test("a wider expiry wins over a narrower one whichever order they arrive in") {
      val repository = stub[RevocationRepository]
      val shortLived = revocation(jti, Instant.EPOCH.plusSeconds(60))
      val longLived = revocation(jti, Instant.EPOCH.plusSeconds(600))
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(shortLived))
        _ <- cache.consume(notificationsOf(longLived))
        _ <- cache.sync
        _ <- TestClock.adjust(61.seconds)
        // An entry that outlives its usefulness costs one map slot until it expires; one cut
        // short accepts a token that was meant to be dead.
        revoked <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(revoked)
    },
    test("reclaims expired entries without going near the database") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- cache.sync
        loaded <- cache.entryCount
        _ <- TestClock.adjust(61.seconds)
        // Reclaiming what has expired is the only thing bounding what this replica holds, so it
        // runs on its own and a database it cannot reach must not be able to stop it.
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- cache.purge
        held <- cache.entryCount
      yield assertTrue(loaded == 1, held == 0)
    },
    test("reads the list a page at a time") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository, RevocationConfig(batchSize = 1))
        // A page that came back full is the only evidence there is another, so the read has to
        // continue past it rather than stop at what one query returned.
        _ <- repository.activeSince.returnsZIO: (cursor, _) =>
          ZIO.succeed:
            if cursor == RevocationCursor.Beginning then fullPage(revocation(jti))
            else if cursor == cursorOf(jti) then fullPage(revocation(sid))
            else onePage()
        _ <- cache.sync
        first <- cache.isRevoked(List(jti), issuedAt)
        second <- cache.isRevoked(List(sid), issuedAt)
      yield assertTrue(first, second, repository.activeSince.calls.size == 3)
    },
    test("asks only for what has been written since the last catch-up") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository, RevocationConfig(overlap = 10.seconds))
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- cache.sync
        _ <- cache.sync
        requested = repository.activeSince.calls.map(_._1)
      yield assertTrue(
        // The first read is the whole list. The second resumes from where it finished, less the
        // overlap that covers a row committed after its `revoked_at` had been passed: staying
        // caught up must not cost what catching up did.
        requested == List(RevocationCursor.Beginning, RevocationCursor(writtenAt.minusSeconds(10), "")),
      )
    },
    test("keeps serving the list it has when a background catch-up fails") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- cache.sync
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- cache.backgroundSync
        // A replica that cannot reach the database can be missing revocations written since it
        // last read, but is never wrong about the ones it already holds.
        stillRevoked <- cache.isRevoked(List(jti), issuedAt)
      yield assertTrue(stillRevoked)
    },
    test("a catch-up that fails leaves the cursor where it was") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository, RevocationConfig(overlap = 10.seconds))
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- cache.sync
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- cache.backgroundSync
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- cache.backgroundSync
        requested = repository.activeSince.calls.map(_._1)
        // The read after the failure asks for the same rows the failed one did, rather than
        // resuming past revocations it never actually saw.
        resumed = RevocationCursor(writtenAt.minusSeconds(10), "")
      yield assertTrue(requested == List(RevocationCursor.Beginning, resumed, resumed))
    },
    test("the startup load propagates its failure rather than swallowing it") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        outcome <- cache.sync.exit
      yield assertTrue(outcome.isFailure)
    },
    test("the layer refuses to come up when the list cannot be read, and comes up when it can") {
      // The whole point of failing the load: an empty list is not a degraded list, it is one that
      // accepts every revoked token presented to it. Asserted on the layer and not just on `sync`,
      // because it is `live` swallowing the failure that would put a replica into service with
      // nothing in it.
      //
      // Both directions, because a layer that fails whatever the database does would satisfy the
      // first assertion for the wrong reason and prove nothing.
      def buildWith(repository: RevocationRepository) =
        ZIO.scoped:
          RevocationCache.live.build.exit.provideSome[Scope](
            ZLayer.succeed(repository),
            ZLayer.succeed[RevocationNotifications](new RevocationNotifications:
              override def notifications = ZStream.empty),
            ZLayer.succeed(RevocationConfig()),
          )

      val unreadable = stub[RevocationRepository]
      val readable = stub[RevocationRepository]
      for
        _ <- unreadable.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- readable.activeSince.succeedsWith(onePage(revocation(jti)))
        refused <- buildWith(unreadable)
        started <- buildWith(readable)
      yield assertTrue(refused.isFailure, started.isSuccess)
    },
    test("a user-wide revocation rejects the tokens that user already held") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- cache.sync
        revoked <- cache.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.minusSeconds(60))
      yield assertTrue(revoked)
    },
    test("a user-wide revocation rejects a token issued in the very second it was written") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- cache.sync
        // `iat` is whole seconds, so a token minted in the revocation's own second cannot be
        // ordered against it, and a tie is read as one of the revoked ones.
        revoked <- cache.isRevoked(List(jti, sid, sub), issuedAt = revokedAt)
      yield assertTrue(revoked)
    },
    test("a user-wide revocation leaves alone a token issued after it") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- cache.sync
        // The user logged in again after the administrator ended their sessions. The entry is
        // still live, and must not lock them out of the session they just started.
        revoked <- cache.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.plusSeconds(1))
      yield assertTrue(!revoked)
    },
    test("a family revocation rejects a token whatever its age, and another family's token passes") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(fam)))
        _ <- cache.sync
        revoked <- cache.isRevoked(List(jti, fam), issuedAt = Instant.EPOCH.plusSeconds(10_000))
        other <- cache.isRevoked(List(jti, RevocationKey.Fam("family-2")), issuedAt)
      yield assertTrue(revoked, !other)
    },
    test("reports a token revoked when the family that issued it is listed") {
      val repository = stub[RevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(fam)))
        _ <- cache.sync
        // The chain leaked, so every token it ever issued goes, including this one, which the
        // entry never names individually.
        revoked <- cache.isRevoked(List(jti, fam, sid, sub), issuedAt)
      yield assertTrue(revoked)
    },
  )
