package versola.util.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** Conformance suite bridging [[RevocationCache]]'s catch-up to a real [[RevocationRepository]]:
  * unlike [[RevocationRepositorySpec]], which only checks what the repository returns, this checks
  * what the cache does with it: specifically, that a second `sync` costs what was written since the
  * first rather than re-reading the whole list. That behaviour lives in how the cache tracks its
  * cursor across calls, so a stub repository (as used in `RevocationCacheSpec`) can't exercise it:
  * the stub has no ordering of its own to get wrong.
  *
  * Nothing here is backend-specific: a storage module extends this and supplies the repository,
  * same as [[RevocationRepositorySpec]].
  */
trait RevocationCacheSyncSpec extends DatabaseSpecBase[RevocationCacheSyncSpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private def revocation(id: String) =
    Revocation(RevocationKey.Jti(id), Instant.now().plusSeconds(3600), issuedBefore = None)

  override def testCases(env: RevocationCacheSyncSpec.Env) =
    List(
      test("a later sync picks up what was written after the last one without re-reading it") {
        for
          cache <- RevocationCache.make(env.repository)
          _ <- env.revoke(revocation("first"))
          _ <- cache.sync
          afterFirst <- cache.entryCount
          _ <- env.revoke(revocation("second"))
          _ <- cache.sync
          afterSecond <- cache.entryCount
          first <- cache.isRevoked(List(RevocationKey.Jti("first")), Instant.now())
          second <- cache.isRevoked(List(RevocationKey.Jti("second")), Instant.now())
        yield assertTrue(afterFirst == 1, afterSecond == 2, first, second)
      } @@ TestAspect.withLiveClock,
    )

object RevocationCacheSyncSpec:
  /** @param revoke writes through the storage's own writer, as in [[RevocationRepositorySpec.Env]]. */
  case class Env(repository: RevocationRepository, revoke: Revocation => Task[Unit])
