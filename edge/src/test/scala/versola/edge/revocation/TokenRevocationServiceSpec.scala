package versola.edge.revocation

import org.scalamock.stubs.ZIOStubs
import versola.edge.OAuthClientService
import versola.edge.model.{
  AccessTokenId,
  ApplicationType,
  AuthorizationPreset,
  ClientCredential,
  ClientId,
  OAuthClient,
  PresetId,
  RefreshTokenFamilyId,
  SessionId,
}
import versola.edge.{AuthorizationPresetsSyncClient, OAuthClientsSyncClient}
import versola.util.revocation.{
  Revocation,
  RevocationCache,
  RevocationCursor,
  RevocationEvent,
  RevocationKey,
  RevocationNotifications,
  RevocationPage,
}
import versola.util.{ReloadingCache, Secret}
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.time.Instant

/** What is edge's own about revocation: how an event auth sent becomes a row and for how long it
  * is kept. How the list is held and read is [[RevocationCache]], and is specified by its own
  * spec in `util`; here it is only the thing `isRevoked` asks.
  */
object TokenRevocationServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val jti = RevocationKey.Jti(AccessTokenId("token-1"))
  private val sid = RevocationKey.Sid(SessionId("session-1"))
  private val sub = RevocationKey.Sub("user-1")
  private val fam = RevocationKey.Fam(RefreshTokenFamilyId("family-1"))

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

  private def revocation(key: RevocationKey, expiresAt: Instant = farFuture): Revocation =
    Revocation(key, expiresAt, issuedBefore = None)

  private def client(id: String, accessTokenTtl: Duration): OAuthClient =
    OAuthClient(
      ClientId(id),
      ClientCredential.ClientSecret(Secret(Array.emptyByteArray)),
      Set.empty,
      accessTokenTtl,
      applicationType = ApplicationType.web,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      redirectUris = Set.empty,
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

  private def serviceOf(repository: EdgeRevocationRepository, clients: OAuthClientService): UIO[TokenRevocationService] =
    RevocationCache.make(repository).map(cache => TokenRevocationService.make(repository, cache, clients))

  private def notificationsOf(revocations: Revocation*): RevocationNotifications =
    new RevocationNotifications:
      override def notifications = ZStream.fromIterable(revocations.map(RevocationEvent.Revoked(_)))

  def spec = suite("TokenRevocationService")(
    test("a revoking replica learns of its own write the same way every other one does") {
      val repository = stub[EdgeRevocationRepository]
      for
        cache <- RevocationCache.make(repository)
        service = TokenRevocationService.make(repository, cache, clientService)
        _ <- repository.activeSince.succeedsWith(onePage())
        _ <- repository.revokeAll.succeedsWith(())
        _ <- cache.sync
        _ <- service.revokeTokens(NonEmptyChunk.single(jti), farFuture)
        // Nothing has told this replica the write landed yet -- not a notification, not a
        // catch-up -- so it answers no differently than one that never made the write.
        beforeNotified <- service.isRevoked(List(jti), issuedAt)
        _ <- cache.consume(notificationsOf(revocation(jti)))
        afterNotified <- service.isRevoked(List(jti), issuedAt)
      yield assertTrue(!beforeNotified, afterNotified)
    },
    test("keeps a session's revocation for as long as the widest access token it could have") {
      val repository = stub[EdgeRevocationRepository]
      for
        // Which clients took part in the session is never looked up, so the entry has to
        // outlive a token of the longest-lived client this edge knows.
        service <- serviceOf(repository, clientServiceOf(client("web", 5.minutes), client("mobile", 1.hour)))
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
      val repository = stub[EdgeRevocationRepository]
      for
        // An edge that has yet to sync a client cannot resolve a TTL, and must not shorten
        // the entry to nothing on account of it.
        service <- serviceOf(repository, clientServiceOf())
        _ <- repository.revokeAll.succeedsWith(())
        now <- Clock.instant
        _ <- service.revokeSession(SessionId("session-1"))
        recorded = repository.revokeAll.calls.head.head
      yield assertTrue(recorded.expiresAt == now.plusSeconds(TokenRevocationService.FallbackAccessTokenTtl.toSeconds))
    },
    test("bounds a user-wide revocation at the moment the administrator acted") {
      val repository = stub[EdgeRevocationRepository]
      val revokedAt = Instant.EPOCH.plusSeconds(600)
      for
        service <- serviceOf(repository, clientServiceOf(client("web", 5.minutes)))
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
    test("writes a family revocation under the bound it was given, unaltered") {
      val repository = stub[EdgeRevocationRepository]
      for
        // Auth derived the bound from the client's TTL and sent it; unlike a session or user
        // revocation there is nothing for this replica to work out for itself.
        service <- serviceOf(repository, clientServiceOf(client("web", 5.minutes)))
        _ <- repository.revokeAll.succeedsWith(())
        _ <- service.revokeTokens(NonEmptyChunk.single(fam), farFuture)
        recorded = repository.revokeAll.calls.head.head
      yield assertTrue(
        recorded.key == fam,
        recorded.expiresAt == farFuture,
        recorded.issuedBefore.isEmpty,
      )
    },
  )
