package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{AccessTokenId, AuthorizationPreset, ClientId, OAuthClient, PresetId}
import versola.edge.{AuthorizationPresetsSyncClient, OAuthClientService, OAuthClientsSyncClient}
import versola.util.{DatabaseSpecBase, ReloadingCache}
import zio.*
import zio.test.*

/** Conformance suite bridging [[TokenRevocationService.sync]] to a real [[RevocationRepository]]:
  * unlike [[RevocationRepositorySpec]], which only checks what the repository returns, this
  * checks what the service does with it — specifically, that a second `sync` costs what was
  * written since the first rather than re-reading the whole list. That behaviour lives in how
  * the service tracks its cursor across calls, so a stub repository (as used in
  * `TokenRevocationServiceSpec`) can't exercise it: the stub has no ordering of its own to get
  * wrong.
  *
  * Nothing here is backend-specific — a backend module extends this and supplies the
  * repository, same as [[RevocationRepositorySpec]].
  */
trait TokenRevocationServiceSyncSpec extends DatabaseSpecBase[TokenRevocationServiceSyncSpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  /** No clients configured: nothing here goes through the TTL-derived paths. */
  private val noClients: OAuthClientService =
    OAuthClientService.Impl(
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset]))),
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ClientId, OAuthClient]))),
      stub[AuthorizationPresetsSyncClient],
      stub[OAuthClientsSyncClient],
    )

  private def revocation(id: String) =
    Revocation(RevocationKey.Jti(AccessTokenId(id)), java.time.Instant.now().plusSeconds(3600), issuedBefore = None)

  override def testCases(env: TokenRevocationServiceSyncSpec.Env) =
    List(
      test("a later sync picks up what was written after the last one without re-reading it") {
        for
          service <- TokenRevocationService.make(env.repository, noClients)
          _ <- env.repository.revokeAll(List(revocation("first")))
          _ <- service.sync
          afterFirst <- service.entryCount
          _ <- env.repository.revokeAll(List(revocation("second")))
          _ <- service.sync
          afterSecond <- service.entryCount
          first <- service.isRevoked(List(RevocationKey.Jti(AccessTokenId("first"))), java.time.Instant.now())
          second <- service.isRevoked(List(RevocationKey.Jti(AccessTokenId("second"))), java.time.Instant.now())
        yield assertTrue(afterFirst == 1, afterSecond == 2, first, second)
      } @@ TestAspect.withLiveClock,
    )

object TokenRevocationServiceSyncSpec:
  case class Env(repository: RevocationRepository)
