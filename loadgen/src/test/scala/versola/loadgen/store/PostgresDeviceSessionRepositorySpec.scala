package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.model.{DeviceSession, SessionKind}
import versola.loadgen.protocol.{EdgeSession, RefreshToken, SsoSession}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

final case class DeviceSessionEnv(repository: DeviceSessionRepository)

/** `vu_sessions` against a real Postgres. Most of what this table is for -- surviving a driver
  * restart with enough of a session left to resume it -- is expressible only as SQL, so this is
  * where the refresh discipline of dev spec §7.4 and the resumability rule of §8.4 are actually
  * checked.
  */
object PostgresDeviceSessionRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[DeviceSessionEnv]:

  private val now = Instant.parse("2026-01-01T12:00:00Z")

  /** A mobile session holds all three credentials it can hold: the refresh token it resumes
    * with, and the SSO session it needs to re-run `/authorize` on (§7.4).
    */
  private def mobile(id: Long, shard: Int, refreshExpiresAt: Instant): DeviceSession =
    DeviceSession(
      id = id,
      userId = id * 10,
      kind = SessionKind.MobileToken,
      clientId = "mobile-otp",
      refreshToken = Some(RefreshToken(s"refresh-$id")),
      edgeCookie = None,
      ssoSession = Some(SsoSession(s"sso-$id")),
      accessExpiresAt = Some(now.plusSeconds(300)),
      refreshExpiresAt = Some(refreshExpiresAt),
      acr = Some("L1"),
      authTime = Some(now.minusSeconds(600)),
      generation = 0,
      refreshGeneration = 0,
      shard = shard,
    )

  /** A web session has no refresh token at all: the `EDGE_SESSION` is the whole credential, and
    * `accessExpiresAt` is its expiry.
    */
  private def web(id: Long, shard: Int, accessExpiresAt: Instant): DeviceSession =
    DeviceSession(
      id = id,
      userId = id * 10,
      kind = SessionKind.WebCookie,
      clientId = "web",
      refreshToken = None,
      edgeCookie = Some(EdgeSession(s"edge-$id")),
      ssoSession = None,
      accessExpiresAt = Some(accessExpiresAt),
      refreshExpiresAt = None,
      acr = Some("L1"),
      authTime = Some(now.minusSeconds(600)),
      generation = 0,
      refreshGeneration = 0,
      shard = shard,
    )

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => DeviceSessionEnv(PostgresDeviceSessionRepository(xa)))

  override def beforeEach(env: DeviceSessionEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_sessions".update.run()).unit

  override def testCases(env: DeviceSessionEnv) = List(
    test("insert then find round-trips every persisted column, in the order V0002 declares them") {
      val session = mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(2592000))
      for
        _     <- env.repository.insert(session)
        found <- env.repository.find(1)
      yield assertTrue(found.contains(session))
    },
    test("a web session round-trips with the cookie and no refresh token, which is not the same as an empty one") {
      val session = web(1, shard = 3, accessExpiresAt = now.plusSeconds(1200))
      for
        _     <- env.repository.insert(session)
        found <- env.repository.find(1)
      yield assertTrue(
        found.contains(session),
        found.flatMap(_.refreshToken).isEmpty,
        found.flatMap(_.edgeCookie).contains(EdgeSession("edge-1")),
      )
    },
    test("find answers None for a session that was never written") {
      env.repository.find(404).map(found => assertTrue(found.isEmpty))
    },
    test("listLive returns a live web session, which has no refresh expiry to be live by") {
      for
        _      <- env.repository.insert(web(1, shard = 3, accessExpiresAt = now.plusSeconds(1200)))
        live   <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
      yield assertTrue(live.map(_.id) == Vector(1L))
    },
    test("listLive orders both kinds by the expiry each is resumable until") {
      for
        _    <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(2592000)))
        _    <- env.repository.insert(web(2, shard = 3, accessExpiresAt = now.plusSeconds(1200)))
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
      yield assertTrue(live.map(_.id) == Vector(2L, 1L))
    },
    test("listLive excludes an expired session of either kind") {
      for
        _    <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.minusSeconds(60)))
        _    <- env.repository.insert(web(2, shard = 3, accessExpiresAt = now.minusSeconds(60)))
        _    <- env.repository.insert(web(3, shard = 3, accessExpiresAt = now.plusSeconds(60)))
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
      yield assertTrue(live.map(_.id) == Vector(3L))
    },
    test("listLive is confined to the driver's own shard") {
      for
        _    <- env.repository.insert(web(1, shard = 3, accessExpiresAt = now.plusSeconds(1200)))
        _    <- env.repository.insert(web(2, shard = 4, accessExpiresAt = now.plusSeconds(1200)))
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
      yield assertTrue(live.map(_.id) == Vector(1L))
    },
    test("listLive honours its limit, taking the sessions that expire soonest") {
      for
        _    <- env.repository.insert(web(1, shard = 3, accessExpiresAt = now.plusSeconds(3600)))
        _    <- env.repository.insert(web(2, shard = 3, accessExpiresAt = now.plusSeconds(1200)))
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 1)
      yield assertTrue(live.map(_.id) == Vector(2L))
    },
    test("listByUser returns one user's sessions in id order and nobody else's") {
      for
        _     <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)).copy(userId = 7))
        _     <- env.repository.insert(mobile(2, shard = 3, refreshExpiresAt = now.plusSeconds(600)).copy(userId = 7))
        _     <- env.repository.insert(mobile(3, shard = 3, refreshExpiresAt = now.plusSeconds(600)).copy(userId = 8))
        found <- env.repository.listByUser(7)
      yield assertTrue(found.map(_.id) == Vector(1L, 2L))
    },
    test("bumpGeneration returns the generation the caller now owns") {
      for
        _      <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        first  <- env.repository.bumpGeneration(1)
        second <- env.repository.bumpGeneration(1)
      yield assertTrue(first.contains(1), second.contains(2))
    },
    test("bumpGeneration answers None for a session that is gone, rather than inventing one") {
      env.repository.bumpGeneration(404).map(bumped => assertTrue(bumped.isEmpty))
    },
    test("storeRotatedRefresh writes at the generation it was given") {
      val rotatedUntil = now.plusSeconds(2592000)
      for
        _          <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        generation <- env.repository.bumpGeneration(1)
        wrote <- env.repository.storeRotatedRefresh(
          id = 1,
          expectedGeneration = generation.get,
          refreshToken = RefreshToken("rotated"),
          refreshExpiresAt = rotatedUntil,
          accessExpiresAt = now.plusSeconds(900),
          acr = Some("L2"),
          authTime = now,
        )
        found <- env.repository.find(1)
      yield assertTrue(
        wrote,
        found.flatMap(_.refreshToken).contains(RefreshToken("rotated")),
        found.flatMap(_.refreshExpiresAt).contains(rotatedUntil),
        found.flatMap(_.acr).contains("L2"),
        found.map(_.generation).contains(1),
      )
    },
    test("storeRotatedRefresh refuses a stale generation instead of resurrecting a retired token") {
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.bumpGeneration(1)
        _ <- env.repository.bumpGeneration(1)
        wrote <- env.repository.storeRotatedRefresh(
          id = 1,
          expectedGeneration = 1,
          refreshToken = RefreshToken("stale"),
          refreshExpiresAt = now.plusSeconds(2592000),
          accessExpiresAt = now.plusSeconds(900),
          acr = None,
          authTime = now,
        )
        found <- env.repository.find(1)
      yield assertTrue(!wrote, found.flatMap(_.refreshToken).contains(RefreshToken("refresh-1")))
    },
    test("storeEdgeCookie adopts the cookie edge rotated, with its new expiry") {
      val rotatedUntil = now.plusSeconds(1800)
      for
        _     <- env.repository.insert(web(1, shard = 3, accessExpiresAt = now.plusSeconds(600)))
        _     <- env.repository.storeEdgeCookie(1, EdgeSession("edge-rotated"), rotatedUntil)
        found <- env.repository.find(1)
      yield assertTrue(
        found.flatMap(_.edgeCookie).contains(EdgeSession("edge-rotated")),
        found.flatMap(_.accessExpiresAt).contains(rotatedUntil),
      )
    },
    test("storeStepUp records the assurance level and the credentials the step-up produced") {
      val steppedUpAt = now.plusSeconds(30)
      val refreshUntil = now.plusSeconds(2592000)
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.storeStepUp(
          id = 1,
          acr = "L2",
          authTime = steppedUpAt,
          accessExpiresAt = now.plusSeconds(900),
          refreshToken = Some(RefreshToken("stepped-up")),
          refreshExpiresAt = Some(refreshUntil),
          ssoSession = Some(SsoSession("sso-rotated")),
        )
        found <- env.repository.find(1)
      yield assertTrue(
        found.flatMap(_.acr).contains("L2"),
        found.flatMap(_.authTime).contains(steppedUpAt),
        found.flatMap(_.refreshToken).contains(RefreshToken("stepped-up")),
        found.flatMap(_.refreshExpiresAt).contains(refreshUntil),
        found.flatMap(_.ssoSession).contains(SsoSession("sso-rotated")),
      )
    },
    test("storeStepUp keeps the credentials the step-up did not rotate, rather than nulling them") {
      val refreshUntil = now.plusSeconds(600)
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = refreshUntil))
        _ <- env.repository.storeStepUp(
          id = 1,
          acr = "L2",
          authTime = now,
          accessExpiresAt = now.plusSeconds(900),
          refreshToken = None,
          refreshExpiresAt = None,
          ssoSession = None,
        )
        found <- env.repository.find(1)
      yield assertTrue(
        found.flatMap(_.acr).contains("L2"),
        found.flatMap(_.refreshToken).contains(RefreshToken("refresh-1")),
        found.flatMap(_.refreshExpiresAt).contains(refreshUntil),
        found.flatMap(_.ssoSession).contains(SsoSession("sso-1")),
      )
    },
    test("a session restored after a restart still carries the SSO session it was established on") {
      for
        _    <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(2592000)))
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
      yield assertTrue(live.flatMap(_.ssoSession) == Vector(SsoSession("sso-1")))
    },
    test("touchAll moves the access expiry of the batch, and of nothing else") {
      val touchedTo = now.plusSeconds(1800)
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.insert(mobile(2, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.touchAll(Chunk(SessionTouch(1, touchedTo)))
        touched   <- env.repository.find(1)
        untouched <- env.repository.find(2)
      yield assertTrue(
        touched.flatMap(_.accessExpiresAt).contains(touchedTo),
        untouched.flatMap(_.accessExpiresAt).contains(now.plusSeconds(300)),
        touched.flatMap(_.refreshToken).contains(RefreshToken("refresh-1")),
      )
    },
    test("touchAll on an empty batch is a no-op, not an empty round trip") {
      env.repository.touchAll(Chunk.empty).as(assertCompletes)
    },
    test("a session left mid-rotation is kept out of listLive rather than resumed on the token it may have spent") {
      // The crash this is about: generation was bumped (step 2 of section 7.4), the driver died,
      // and the row still holds the predecessor token. The generation alone cannot say so -- it
      // reads exactly as it does after a rotation that completed -- so resuming on what is
      // stored risks presenting a token the SUT has already rotated, which is reuse.
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(2592000)))
        _ <- env.repository.insert(mobile(2, shard = 3, refreshExpiresAt = now.plusSeconds(2592000)))
        _ <- env.repository.bumpGeneration(2)
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
        stranded <- env.repository.listInterruptedRotations(shard = 3, limit = 10)
        found <- env.repository.find(2)
      yield assertTrue(
        live.map(_.id) == Vector(1L),
        stranded.map(_.id) == Vector(2L),
        found.exists(_.rotationInFlight),
        live.forall(!_.rotationInFlight),
      )
    },
    test("a rotation that completed leaves nothing for recovery to retire") {
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        generation <- env.repository.bumpGeneration(1)
        _ <- env.repository.storeRotatedRefresh(
          id = 1,
          expectedGeneration = generation.get,
          refreshToken = RefreshToken("rotated"),
          refreshExpiresAt = now.plusSeconds(2592000),
          accessExpiresAt = now.plusSeconds(900),
          acr = None,
          authTime = now,
        )
        live <- env.repository.listLive(shard = 3, liveAt = now, limit = 10)
        stranded <- env.repository.listInterruptedRotations(shard = 3, limit = 10)
        found <- env.repository.find(1)
      yield assertTrue(
        live.map(_.id) == Vector(1L),
        stranded.isEmpty,
        found.exists(!_.rotationInFlight),
      )
    },
    test("listInterruptedRotations is confined to the driver's own shard") {
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.insert(mobile(2, shard = 4, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.bumpGeneration(1)
        _ <- env.repository.bumpGeneration(2)
        stranded <- env.repository.listInterruptedRotations(shard = 3, limit = 10)
      yield assertTrue(stranded.map(_.id) == Vector(1L))
    },
    test("storeRotatedRefresh keeps the assurance level when the exchange said nothing about it") {
      // The token response carries no ACR and a refresh cannot lower assurance, so None means
      // "unchanged" here exactly as it does in storeStepUp -- writing it through would demote a
      // stepped-up session and have the driver step it up again after a restart.
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _ <- env.repository.storeStepUp(
          id = 1,
          acr = "L2",
          authTime = now,
          accessExpiresAt = now.plusSeconds(900),
          refreshToken = None,
          refreshExpiresAt = None,
          ssoSession = None,
        )
        generation <- env.repository.bumpGeneration(1)
        wrote <- env.repository.storeRotatedRefresh(
          id = 1,
          expectedGeneration = generation.get,
          refreshToken = RefreshToken("rotated"),
          refreshExpiresAt = now.plusSeconds(2592000),
          accessExpiresAt = now.plusSeconds(1200),
          acr = None,
          authTime = now,
        )
        found <- env.repository.find(1)
      yield assertTrue(wrote, found.flatMap(_.acr).contains("L2"))
    },
    test("a deferred touch cannot pull the access expiry back over a critical write that overtook it") {
      // A touch is queued before it is applied, so one queued ahead of a rotation can reach the
      // table after it. access_expires_at is a web session's liveness boundary in listLive, so a
      // regression here discards sessions that are still resumable.
      val rotatedTo = now.plusSeconds(1800)
      for
        _ <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(2592000)))
        generation <- env.repository.bumpGeneration(1)
        _ <- env.repository.storeRotatedRefresh(
          id = 1,
          expectedGeneration = generation.get,
          refreshToken = RefreshToken("rotated"),
          refreshExpiresAt = now.plusSeconds(2592000),
          accessExpiresAt = rotatedTo,
          acr = None,
          authTime = now,
        )
        _ <- env.repository.touchAll(Chunk(SessionTouch(1, now.plusSeconds(600))))
        stale <- env.repository.find(1)
        _ <- env.repository.touchAll(Chunk(SessionTouch(1, now.plusSeconds(3600))))
        ahead <- env.repository.find(1)
      yield assertTrue(
        stale.flatMap(_.accessExpiresAt).contains(rotatedTo),
        ahead.flatMap(_.accessExpiresAt).contains(now.plusSeconds(3600)),
      )
    },
    test("a touch is still the first value a session with no access expiry gets") {
      val touchedTo = now.plusSeconds(1800)
      for
        _ <- env.repository.insert(
          mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)).copy(accessExpiresAt = None),
        )
        _ <- env.repository.touchAll(Chunk(SessionTouch(1, touchedTo)))
        found <- env.repository.find(1)
      yield assertTrue(found.flatMap(_.accessExpiresAt).contains(touchedTo))
    },
    test("delete removes the session the recovery path retired") {
      for
        _     <- env.repository.insert(mobile(1, shard = 3, refreshExpiresAt = now.plusSeconds(600)))
        _     <- env.repository.delete(1)
        found <- env.repository.find(1)
      yield assertTrue(found.isEmpty)
    },
  )
