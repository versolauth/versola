package versola.edge.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{AccessTokenId, PresetId, SessionId}
import versola.util.{DatabaseSpecBase, Secret}
import zio.*
import zio.test.*

import java.time.Instant

/** Conformance suite for any [[EdgeSessionRepository]] implementation. A backend module
  * extends this and supplies the wiring -- see `PostgresEdgeSessionRepositorySpec` in
  * `edge-postgres-impl` for the one binding that exists today.
  */
trait EdgeSessionRepositorySpec extends DatabaseSpecBase[EdgeSessionRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val sid1 = SessionId("sid-1")
  private val sid2 = SessionId("sid-2")
  private val preset1 = PresetId("preset-1")
  private val preset2 = PresetId("preset-2")

  // Projected away from `encryptedRefreshToken` directly, since `Secret` wraps a byte array
  // and case-class equality on it is reference equality, not content equality -- a value
  // round-tripped through Postgres never `==`s the one that went in even when the bytes match.
  private def summarize(r: EdgeSessionRecord) =
    (
      r.publicSessionId,
      r.presetId,
      r.accessTokenId,
      r.encryptedRefreshToken.map(_.toList),
      r.expiresAt,
      r.previousAccessTokenId,
      r.encryptedAccessToken.map(_.toList),
    )

  private def record(
      sid: SessionId,
      preset: PresetId,
      accessTokenId: AccessTokenId,
      refreshToken: Option[Secret],
      expiresAt: Instant,
      accessToken: Option[Secret] = None,
  ) = EdgeSessionRecord(
    publicSessionId = sid,
    presetId = preset,
    accessTokenId = accessTokenId,
    encryptedRefreshToken = refreshToken,
    expiresAt = expiresAt,
    encryptedAccessToken = accessToken,
  )

  private val leaseLength = 10.seconds

  override def testCases(env: EdgeSessionRepositorySpec.Env) =
    List(
      test("findByAccessTokenId returns None when nothing matches") {
        for found <- env.repository.findByAccessTokenId(AccessTokenId("missing"))
        yield assertTrue(found.isEmpty)
      },
      test("create stores a session retrievable by its access token id") {
        for
          now <- Clock.instant
          r = record(sid1, preset1, AccessTokenId("at-1"), Some(Secret.fromString("rt-1")), now.plusSeconds(3600))
          _ <- env.repository.create(r)
          found <- env.repository.findByAccessTokenId(AccessTokenId("at-1"))
        yield assertTrue(found.map(summarize) == Some(summarize(r)))
      },
      test("create upserts on (publicSessionId, presetId), rotating the access token id") {
        for
          now <- Clock.instant
          first = record(sid1, preset1, AccessTokenId("at-old"), Some(Secret.fromString("rt-old")), now.plusSeconds(3600))
          second = record(sid1, preset1, AccessTokenId("at-new"), Some(Secret.fromString("rt-new")), now.plusSeconds(7200))
          _ <- env.repository.create(first)
          _ <- env.repository.create(second)
          oldFound <- env.repository.findByAccessTokenId(AccessTokenId("at-old"))
          newFound <- env.repository.findByAccessTokenId(AccessTokenId("at-new"))
        yield assertTrue(
          oldFound.isEmpty,
          newFound.map(summarize) == Some(summarize(second.copy(previousAccessTokenId = Some(AccessTokenId("at-old"))))),
        )
      },
      test("create sets previousAccessTokenId to the id the row had before, and leaves it empty on first write") {
        for
          now <- Clock.instant
          first =
            record(sid1, preset1, AccessTokenId("at-old"), Some(Secret.fromString("rt-old")), now.plusSeconds(3600), Some(Secret.fromString("a-old")))
          second =
            record(sid1, preset1, AccessTokenId("at-new"), Some(Secret.fromString("rt-new")), now.plusSeconds(7200), Some(Secret.fromString("a-new")))
          _ <- env.repository.create(first)
          afterFirst <- env.repository.findByAccessTokenId(AccessTokenId("at-old"))
          _ <- env.repository.create(second)
          afterSecond <- env.repository.findByAccessTokenId(AccessTokenId("at-new"))
        yield assertTrue(
          afterFirst.flatMap(_.previousAccessTokenId).isEmpty,
          afterSecond.flatMap(_.previousAccessTokenId) == Some(AccessTokenId("at-old")),
          afterSecond.flatMap(_.encryptedAccessToken).map(_.toList) == Some(Secret.fromString("a-new").toList),
        )
      },
      test("findByPreviousAccessTokenId returns the rotated row, and nothing for an id that was never rotated away") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-old"), None, now.plusSeconds(3600)))
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-new"), None, now.plusSeconds(3600)))
          rotated <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), now.minusSeconds(10))
          current <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-new"), now.minusSeconds(10))
          unknown <- env.repository.findByPreviousAccessTokenId(AccessTokenId("missing"), now.minusSeconds(10))
        yield assertTrue(
          rotated.map(_.accessTokenId) == Some(AccessTokenId("at-new")),
          current.isEmpty,
          unknown.isEmpty,
        )
      },
      test("findByPreviousAccessTokenId excludes sessions that have already expired") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-old"), None, now.plusSeconds(60)))
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-new"), None, now.plusSeconds(60)))
          before <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), now.minusSeconds(10))
          _ <- TestClock.adjust(2.minutes)
          after <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), now.minusSeconds(10))
        yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("findByPreviousAccessTokenId honours the rotation window: inside it the row is found, at its edge and after it not") {
        for
          start <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-old"), None, start.plusSeconds(3600)))
          _ <- TestClock.adjust(1.second)
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-new"), None, start.plusSeconds(3600)))
          rotatedAt <- Clock.instant
          inside <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), rotatedAt.minusSeconds(10))
          onEdge <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), rotatedAt)
          after <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-old"), rotatedAt.plusSeconds(1))
        yield assertTrue(inside.isDefined, inside.flatMap(_.rotatedAt) == Some(rotatedAt), onEdge.isEmpty, after.isEmpty)
      },
      test("a row that was never rotated has no rotatedAt and is not found by previous id") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(3600)))
          found <- env.repository.findByAccessTokenId(AccessTokenId("at-1"))
          byPrevious <- env.repository.findByPreviousAccessTokenId(AccessTokenId("at-1"), now.minusSeconds(10))
        yield assertTrue(found.flatMap(_.rotatedAt).isEmpty, byPrevious.isEmpty)
      },
      test("claimRefresh is exclusive: a second claim on the same row finds the lease taken") {
        for
          now <- Clock.instant
          r = record(sid1, preset1, AccessTokenId("at-1"), Some(Secret.fromString("rt-1")), now.plusSeconds(3600))
          _ <- env.repository.create(r)
          first <- env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength))
          second <- env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength))
        yield assertTrue(first.map(summarize) == Some(summarize(r)), second.isEmpty)
      },
      test("claimRefresh admits exactly one of many concurrent claims") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(3600)))
          results <- ZIO.foreachPar(1 to 20)(_ => env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength)))
        yield assertTrue(results.count(_.isDefined) == 1)
      },
      test("claimRefresh finds nothing for an unknown id, a rotated-away id, or an expired row") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-old"), None, now.plusSeconds(3600)))
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-new"), None, now.plusSeconds(3600)))
          _ <- env.repository.create(record(sid2, preset1, AccessTokenId("at-short"), None, now.plusSeconds(60)))
          unknown <- env.repository.claimRefresh(AccessTokenId("missing"), now, now.plus(leaseLength))
          rotated <- env.repository.claimRefresh(AccessTokenId("at-old"), now, now.plus(leaseLength))
          later = now.plusSeconds(120)
          expired <- env.repository.claimRefresh(AccessTokenId("at-short"), later, later.plus(leaseLength))
        yield assertTrue(unknown.isEmpty, rotated.isEmpty, expired.isEmpty)
      },
      test("claimRefresh succeeds again once the lease has run out") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(3600)))
          _ <- env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength))
          during <- env.repository.claimRefresh(AccessTokenId("at-1"), now.plusSeconds(5), now.plusSeconds(15))
          after <- env.repository.claimRefresh(AccessTokenId("at-1"), now.plusSeconds(11), now.plusSeconds(21))
        yield assertTrue(during.isEmpty, after.isDefined)
      },
      test("releaseRefresh lets the row be claimed again") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(3600)))
          _ <- env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength))
          _ <- env.repository.releaseRefresh(AccessTokenId("at-1"))
          again <- env.repository.claimRefresh(AccessTokenId("at-1"), now, now.plus(leaseLength))
        yield assertTrue(again.isDefined)
      },
      test("create ends the lease on the row it rotates") {
        for
          now <- Clock.instant
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-old"), None, now.plusSeconds(3600)))
          _ <- env.repository.claimRefresh(AccessTokenId("at-old"), now, now.plus(leaseLength))
          _ <- env.repository.create(record(sid1, preset1, AccessTokenId("at-new"), None, now.plusSeconds(3600)))
          claimed <- env.repository.claimRefresh(AccessTokenId("at-new"), now, now.plus(leaseLength))
        yield assertTrue(claimed.isDefined)
      },
      test("findByAccessTokenId excludes sessions that have already expired") {
        for
          now <- Clock.instant
          r = record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(60))
          _ <- env.repository.create(r)
          before <- env.repository.findByAccessTokenId(AccessTokenId("at-1"))
          _ <- TestClock.adjust(2.minutes)
          after <- env.repository.findByAccessTokenId(AccessTokenId("at-1"))
        yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("findBySessionId returns every preset participation for a session, ignoring expiry") {
        for
          now <- Clock.instant
          live = record(sid1, preset1, AccessTokenId("at-live"), None, now.plusSeconds(3600))
          expired = record(sid1, preset2, AccessTokenId("at-expired"), None, now.plusSeconds(60))
          other = record(sid2, preset1, AccessTokenId("at-other"), None, now.plusSeconds(3600))
          _ <- env.repository.create(live)
          _ <- env.repository.create(expired)
          _ <- env.repository.create(other)
          _ <- TestClock.adjust(2.minutes)
          found <- env.repository.findBySessionId(sid1)
        yield assertTrue(found.map(summarize).toSet == Set(summarize(live), summarize(expired)))
      },
      test("delete removes the session by access token id") {
        for
          now <- Clock.instant
          r = record(sid1, preset1, AccessTokenId("at-1"), None, now.plusSeconds(3600))
          _ <- env.repository.create(r)
          _ <- env.repository.delete(AccessTokenId("at-1"))
          found <- env.repository.findByAccessTokenId(AccessTokenId("at-1"))
        yield assertTrue(found.isEmpty)
      },
    )

object EdgeSessionRepositorySpec:
  case class Env(repository: EdgeSessionRepository)
