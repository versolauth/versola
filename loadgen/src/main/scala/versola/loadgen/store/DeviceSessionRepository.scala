package versola.loadgen.store

import versola.loadgen.model.DeviceSession
import versola.loadgen.protocol.{EdgeSession, RefreshToken, SsoSession}
import zio.{Chunk, Task}

import java.time.Instant

/** `vu_sessions` (migration V0002). The refresh discipline of dev spec §7.4 lives in the
  * signatures here, not in the caller's discretion: [[bumpGeneration]] returns the generation
  * the caller now owns, and [[storeRotatedRefresh]] refuses to write against any other one.
  */
trait DeviceSessionRepository:

  def insert(session: DeviceSession): Task[Unit]

  def find(id: Long): Task[Option[DeviceSession]]

  def listByUser(userId: Long): Task[Vector[DeviceSession]]

  /** This driver's sessions still resumable at `liveAt`, oldest expiry first, served by
    * `vu_sessions_shard_idx`. What a driver loads at startup to decide which users can resume
    * rather than log in again.
    *
    * Resumable means whichever credential the session's kind depends on has not expired: the
    * refresh token for a mobile session, and for a web-cookie session -- which has no refresh
    * token -- the `EDGE_SESSION` itself, whose expiry is `accessExpiresAt`. Filtering on
    * `refreshExpiresAt` alone would drop every live web session at startup and shift its
    * traffic to fresh logins, changing the scenario mix the driver reports it ran.
    */
  def listLive(shard: Int, liveAt: Instant, limit: Int): Task[Vector[DeviceSession]]

  /** Step 2 of §7.4, executed and awaited *before* the token request goes out. Returns the new
    * generation, or `None` if the session is gone.
    *
    * Persisting the intent first is what makes a driver crash recoverable: a session whose
    * generation was bumped but whose `refresh_token` still holds the predecessor is in an
    * unknown state on restart and must be retired rather than replayed -- replaying it trips
    * the SUT's reuse detection and produces a fake `refresh_rejected`, the one metric the
    * campaign is not allowed to have.
    */
  def bumpGeneration(id: Long): Task[Option[Int]]

  /** Step 4 of §7.4, awaited before the new access token is used.
    *
    * `expectedGeneration` is the value [[bumpGeneration]] returned. The UPDATE is conditional
    * on it, so a response that arrives after the session was retired and re-bumped by the
    * recovery path cannot resurrect a token nobody owns any more. Returns whether the row was
    * still at that generation, i.e. whether the write took.
    */
  def storeRotatedRefresh(
      id: Long,
      expectedGeneration: Int,
      refreshToken: RefreshToken,
      refreshExpiresAt: Instant,
      accessExpiresAt: Instant,
      acr: Option[String],
      authTime: Instant,
  ): Task[Boolean]

  /** The cookie half of the same rule (§8.4): edge rotates `EDGE_SESSION` on refresh, and a
    * driver that loses the rotated value loses the session. Critical, not deferred.
    */
  def storeEdgeCookie(id: Long, cookie: EdgeSession, accessExpiresAt: Instant): Task[Unit]

  /** What a completed step-up leaves behind (§7.4): the session's assurance level, the fresh
    * `auth_time` behind it, the token pair the new code exchange produced, and the
    * `SSO_SESSION` if auth re-set it on the way through.
    *
    * `ssoSession` is `None` when the response carried no new cookie, which leaves the stored
    * one alone rather than clearing it -- the same `COALESCE` rule as the token pair.
    *
    * The step-up path's counterpart to [[storeRotatedRefresh]], and deliberately not guarded by
    * a generation: a step-up is an authorization-code exchange on the same SSO session, not a
    * refresh exchange, so nothing was bumped and there is no predecessor token the SUT could
    * detect as reused.
    *
    * Critical rather than deferred, which is where dev spec §7.5 puts `acr`. The write costs
    * nothing extra here (the token beside it has to be persisted anyway), and a dropped `acr`
    * would leave a resumed session claiming an assurance level it does not hold -- so the
    * driver would either step up again for no reason, distorting the scenario mix it reports,
    * or skip a step-up the SUT then demands.
    */
  def storeStepUp(
      id: Long,
      acr: String,
      authTime: Instant,
      accessExpiresAt: Instant,
      refreshToken: Option[RefreshToken],
      refreshExpiresAt: Option[Instant],
      ssoSession: Option[SsoSession],
  ): Task[Unit]

  /** Deferred write path -- `access_expires_at` only (§7.5, less `acr`; see [[SessionTouch]]). */
  def touchAll(touches: Chunk[SessionTouch]): Task[Unit]

  /** Removes a session that was logged out, expired, or retired by the recovery path above. */
  def delete(id: Long): Task[Unit]
