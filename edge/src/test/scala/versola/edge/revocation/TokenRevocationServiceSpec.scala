package versola.edge.revocation

import org.scalamock.stubs.ZIOStubs
import versola.edge.{AuthorizationPresetsSyncClient, EdgeConfig, OAuthClientService, OAuthClientsSyncClient}
import versola.edge.model.{AccessTokenId, AuthorizationPreset, ClientId, EdgeId, OAuthClient, PresetId, SessionId}
import versola.util.{ReloadingCache, Secret}
import zio.*
import zio.http.URL
import zio.stream.ZStream
import zio.test.*

import java.security.KeyPairGenerator
import java.time.Instant

object TokenRevocationServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val jti = RevocationKey.Jti(AccessTokenId("token-1"))
  private val sid = RevocationKey.Sid(SessionId("session-1"))
  private val sub = RevocationKey.Sub("user-1")
  private val otherJti = RevocationKey.Jti(AccessTokenId("token-2"))

  private val farFuture = Instant.EPOCH.plusSeconds(3600)

  /** When the token under test was issued. Only entries carrying an `issuedBefore` care. */
  private val issuedAt = Instant.EPOCH

  /** When the rows the repository hands back were written. Only the cursor cares. */
  private val writtenAt = Instant.EPOCH.plusSeconds(100)

  private def cursorOf(key: RevocationKey): RevocationCursor = RevocationCursor(writtenAt, key.encoded)

  /** Everything asked for, in one read: what the repository returns when the list fits
    * inside a single page.
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

  private def client(id: String, accessTokenTtl: Duration): OAuthClient =
    OAuthClient(ClientId(id), Secret(Array.emptyByteArray), Set.empty, accessTokenTtl)

  /** Only [[EdgeConfig.revocation]] matters here; the rest is what the case class demands. */
  private lazy val edgeConfig = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey =
      val generator = KeyPairGenerator.getInstance("RSA").nn
      generator.initialize(2048)
      generator.generateKeyPair().nn.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  /** A fresh one per test: the cache behind it is mutable, and a leftover client from
    * another test would silently change the TTL under examination.
    */
  private def clientServiceOf(values: OAuthClient*): OAuthClientService =
    OAuthClientService.Impl(
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset]))),
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(values.map(c => c.id -> c).toMap))),
      stub[AuthorizationPresetsSyncClient],
      stub[OAuthClientsSyncClient],
    )

  private val clientService = clientServiceOf(client("web", 5.minutes))

  private def notificationsOf(revocations: Revocation*): RevocationNotifications =
    eventsOf(revocations.map(RevocationEvent.Revoked(_))*)

  private def eventsOf(events: RevocationEvent*): RevocationNotifications =
    new RevocationNotifications:
      override def notifications = ZStream.fromIterable(events)

  def spec = suite("TokenRevocationService")(
    test("answers from memory, never from the database") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- service.sync
        revoked <- service.isRevoked(List(jti), issuedAt)
        live <- service.isRevoked(List(otherJti), issuedAt)
      yield assertTrue(
        revoked,
        !live,
        // One call, made by the load itself. Neither answer cost anything beyond it, and a
        // miss cost no more than a hit: what an unrevoked token pays is not a caller's to
        // influence by presenting one that misses.
        repository.activeSince.calls.size == 1,
      )
    },
    test("stops honouring an entry once the token it names would have expired anyway") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- service.sync
        beforeExpiry <- service.isRevoked(List(jti), issuedAt)
        _ <- TestClock.adjust(61.seconds)
        afterExpiry <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(beforeExpiry, !afterExpiry)
    },
    test("reports a token revoked when any of its keys is listed, including its session") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(sid)))
        _ <- service.sync
        revoked <- service.isRevoked(List(jti, sid), issuedAt)
      yield assertTrue(revoked)
    },
    test("a revoking replica learns of its own write the same way every other one does") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- repository.revokeAll.succeedsWith(())
        _ <- service.sync
        _ <- service.revokeToken(AccessTokenId("token-1"), farFuture)
        // Nothing has told this replica the write landed yet -- not a notification, not a
        // catch-up -- so it answers no differently than one that never made the write.
        beforeNotified <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(notificationsOf(revocation(jti)))
        afterNotified <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!beforeNotified, afterNotified)
    },
    test("applies revocations announced by another replica") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- service.sync
        before <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(notificationsOf(revocation(jti)))
        after <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("catches up when the notification feed reconnects") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        // Written while the feed was down, so its notification was never delivered: only a
        // reload can find it, which is what the reconnect has to trigger.
        before <- service.isRevoked(List(jti), issuedAt)
        _ <- service.consume(eventsOf(RevocationEvent.Resubscribed))
        after <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!before, after)
    },
    test("keeps a revocation that arrived by notification while a reload was in flight") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        // What the database returns does not include this one: a reload merges into what
        // the replica already holds rather than replacing it, because replacing would drop
        // every revocation newer than the query.
        _ <- repository.activeSince.succeedsWith(onePage(revocation(sid)))
        _ <- service.consume(notificationsOf(revocation(jti)))
        _ <- service.sync
        delivered <- service.isRevoked(List(jti), issuedAt)
        loaded <- service.isRevoked(List(sid), issuedAt)
      yield assertTrue(delivered, loaded)
    },
    test("a stale page cannot undo a wider revocation that landed while it was in flight") {
      val repository = stub[RevocationRepository]
      val firstInvalidation = Instant.EPOCH.plusSeconds(600)
      val secondInvalidation = firstInvalidation.plusSeconds(60)
      for
        service <- TokenRevocationService.make(repository, clientService)
        // What the catch-up's query saw: the row as it stood before the user was invalidated
        // a second time.
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(firstInvalidation)))
        // The second invalidation widens the row in place, and its notification gets there
        // first. Applying the page afterwards must not put the earlier bound back.
        _ <- service.consume(notificationsOf(userRevocation(secondInvalidation)))
        _ <- service.sync
        // Issued between the two invalidations: covered by the second, not by the first.
        revoked <- service.isRevoked(List(sub), issuedAt = firstInvalidation.plusSeconds(30))
      yield assertTrue(revoked)
    },
    test("a wider expiry wins over a narrower one whichever order they arrive in") {
      val repository = stub[RevocationRepository]
      val shortLived = revocation(jti, Instant.EPOCH.plusSeconds(60))
      val longLived = revocation(jti, Instant.EPOCH.plusSeconds(600))
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(shortLived))
        _ <- service.consume(notificationsOf(longLived))
        _ <- service.sync
        _ <- TestClock.adjust(61.seconds)
        // An entry that outlives its usefulness costs one map slot until it expires; one cut
        // short accepts a token that was meant to be dead.
        revoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(revoked)
    },
    test("reclaims expired entries without going near the database") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti, Instant.EPOCH.plusSeconds(60))))
        _ <- service.sync
        loaded <- service.entryCount
        _ <- TestClock.adjust(61.seconds)
        // Reclaiming what has expired is the only thing bounding what this replica holds, so
        // it runs on its own and a database it cannot reach must not be able to stop it.
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- service.purge
        held <- service.entryCount
      yield assertTrue(loaded == 1, held == 0)
    },
    test("reads the list a page at a time") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService, EdgeConfig.Revocation(batchSize = 1))
        // A page that came back full is the only evidence there is another, so the read has
        // to continue past it rather than stop at what one query returned.
        _ <- repository.activeSince.returnsZIO: (cursor, _) =>
          ZIO.succeed:
            if cursor == RevocationCursor.Beginning then fullPage(revocation(jti))
            else if cursor == cursorOf(jti) then fullPage(revocation(sid))
            else onePage()
        _ <- service.sync
        first <- service.isRevoked(List(jti), issuedAt)
        second <- service.isRevoked(List(sid), issuedAt)
      yield assertTrue(first, second, repository.activeSince.calls.size == 3)
    },
    test("asks only for what has been written since the last catch-up") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService, EdgeConfig.Revocation(overlap = 10.seconds))
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- service.sync
        _ <- service.sync
        requested = repository.activeSince.calls.map(_._1)
      yield assertTrue(
        // The first read is the whole list. The second resumes from where it finished, less
        // the overlap that covers a row committed after its `revoked_at` had been passed:
        // staying caught up must not cost what catching up did.
        requested == List(RevocationCursor.Beginning, RevocationCursor(writtenAt.minusSeconds(10), "")),
      )
    },
    test("keeps serving the list it has when a background catch-up fails") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- service.sync
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- service.backgroundSync
        // A replica that cannot reach the database can be missing revocations written since
        // it last read, but is never wrong about the ones it already holds.
        stillRevoked <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(stillRevoked)
    },
    test("a catch-up that fails leaves the cursor where it was") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService, EdgeConfig.Revocation(overlap = 10.seconds))
        _ <- repository.activeSince.succeedsWith(onePage(revocation(jti)))
        _ <- service.sync
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        _ <- service.backgroundSync
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- service.backgroundSync
        requested = repository.activeSince.calls.map(_._1)
        // The read after the failure asks for the same rows the failed one did, rather than
        // resuming past revocations it never actually saw.
        resumed = RevocationCursor(writtenAt.minusSeconds(10), "")
      yield assertTrue(requested == List(RevocationCursor.Beginning, resumed, resumed))
    },
    test("the startup load propagates its failure rather than swallowing it") {
      val repository = stub[RevocationRepository]
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.failsWith(RuntimeException("connection refused"))
        outcome <- service.sync.exit
      yield assertTrue(outcome.isFailure)
    },
    test("the layer refuses to come up when the list cannot be read, and comes up when it can") {
      // The whole point of failing the load: an empty list is not a degraded list, it is one
      // that accepts every revoked token presented to it. Asserted on the layer and not just
      // on `sync`, because it is `live` swallowing the failure that would put a replica into
      // service with nothing in it.
      //
      // Both directions, because a layer that fails whatever the database does would satisfy
      // the first assertion for the wrong reason and prove nothing.
      def buildWith(repository: RevocationRepository) =
        ZIO.scoped:
          TokenRevocationService.live.build.exit.provideSome[Scope](
            ZLayer.succeed(repository),
            ZLayer.succeed[RevocationNotifications](new RevocationNotifications:
              override def notifications = ZStream.empty),
            ZLayer.succeed(clientService),
            ZLayer.succeed(edgeConfig),
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
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- service.sync
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.minusSeconds(60))
      yield assertTrue(revoked)
    },
    test("a user-wide revocation leaves alone a token issued after it") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- service.sync
        // The user logged in again after the administrator ended their sessions. The entry
        // is still live, and must not lock them out of the session they just started.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt.plusSeconds(1))
      yield assertTrue(!revoked)
    },
    test("keeps a session's revocation for as long as the widest access token it could have") {
      val repository = stub[RevocationRepository]
      for
        // Which clients took part in the session is never looked up, so the entry has to
        // outlive a token of the longest-lived client this edge knows.
        service <- TokenRevocationService.make(repository, clientServiceOf(client("web", 5.minutes), client("mobile", 1.hour)))
        _ <- repository.revokeAll.succeedsWith(())
        now <- Clock.instant
        _ <- service.revokeSession(SessionId("session-1"))
        recorded = repository.revokeAll.calls.head.head
      yield assertTrue(
        recorded.key == sid,
        recorded.expiresAt == now.plusSeconds(1.hour.toSeconds),
        recorded.issuedBefore.isEmpty,
      )
    },
    test("falls back to a covering TTL when it knows of no client at all") {
      val repository = stub[RevocationRepository]
      for
        // An edge that has yet to sync a client cannot resolve a TTL, and must not shorten
        // the entry to nothing on account of it.
        service <- TokenRevocationService.make(repository, clientServiceOf())
        _ <- repository.revokeAll.succeedsWith(())
        now <- Clock.instant
        _ <- service.revokeSession(SessionId("session-1"))
        recorded = repository.revokeAll.calls.head.head
      yield assertTrue(recorded.expiresAt == now.plusSeconds(TokenRevocationService.FallbackAccessTokenTtl.toSeconds))
    },
    test("bounds a user-wide revocation at the moment the administrator acted") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository, clientServiceOf(client("web", 5.minutes)))
        _ <- repository.revokeAll.succeedsWith(())
        _ <- service.revokeUser("user-1", revokedAt)
        recorded = repository.revokeAll.calls.head.head
      yield assertTrue(
        recorded.key == sub,
        // Measured from the event, not from now: the entry expires once the last token that
        // predates it would have.
        recorded.expiresAt == revokedAt.plusSeconds(5.minutes.toSeconds),
        recorded.issuedBefore.contains(revokedAt),
      )
    },
    test("a user-wide revocation covers a token issued in the same second as itself") {
      val repository = stub[RevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- TokenRevocationService.make(repository, clientService)
        _ <- repository.activeSince.succeedsWith(onePage(userRevocation(revokedAt)))
        _ <- service.sync
        // `iat` is whole seconds, so the two cannot be ordered. Sparing the token would let
        // one the administrator meant to end through; the tie goes to the revocation.
        revoked <- service.isRevoked(List(jti, sid, sub), issuedAt = revokedAt)
      yield assertTrue(revoked)
    },
  )
