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
    (r.publicSessionId, r.presetId, r.accessTokenId, r.encryptedRefreshToken.map(_.toList), r.expiresAt)

  private def record(
      sid: SessionId,
      preset: PresetId,
      accessTokenId: AccessTokenId,
      refreshToken: Option[Secret],
      expiresAt: Instant,
  ) = EdgeSessionRecord(
    publicSessionId = sid,
    presetId = preset,
    accessTokenId = accessTokenId,
    encryptedRefreshToken = refreshToken,
    expiresAt = expiresAt,
  )

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
          newFound.map(summarize) == Some(summarize(second)),
        )
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
