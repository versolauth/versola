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

  /** The sessions this driver left between step 2 and step 4 of [[bumpGeneration]]'s discipline:
    * `generation` was bumped, but the refresh token beside it is still the predecessor, because
    * the driver died before [[storeRotatedRefresh]] ran.
    *
    * They are deliberately absent from [[listLive]]. The generation alone cannot tell a
    * completed rotation from an interrupted one -- both leave the row at the bumped value -- so
    * the row also records the generation its token was written at, and the two agreeing is what
    * makes a session resumable. Without that, recovery would resume a session on a token the
    * SUT may have already rotated, which is exactly the reuse the discipline exists to avoid.
    *
    * What recovery does with these is [[delete]] them: the user logs in again, which costs one
    * login and keeps `refresh_rejected` at zero.
    */
  def listInterruptedRotations(shard: Int, limit: Int): Task[Vector[DeviceSession]]

  /** Step 2 of §7.4, executed and awaited *before* the token request goes out. Returns the new
    * generation, or `None` if the session is gone.
    *
    * Persisting the intent first is what makes a driver crash recoverable: a session whose
    * generation was bumped but whose `refresh_token` still holds the predecessor is in an
    * unknown state on restart and must be retired rather than replayed -- replaying it trips
    * the SUT's reuse detection and produces a fake `refresh_rejected`, the one metric the
    * campaign is not allowed to have.
    *
    * Only `generation` moves here; the row's `refresh_generation` stays where the stored token
    * was written, which is what makes the two states distinguishable after a crash. See
    * [[listInterruptedRotations]].
    */
  def bumpGeneration(id: Long): Task[Option[Int]]

  /** Step 4 of §7.4, awaited before the new access token is used.
    *
    * `expectedGeneration` is the value [[bumpGeneration]] returned. The UPDATE is conditional
    * on it, so a response that arrives after the session was retired and re-bumped by the
    * recovery path cannot resurrect a token nobody owns any more. Returns whether the row was
    * still at that generation, i.e. whether the write took.
    *
    * It also carries the row's `refresh_generation` up to the bumped `generation`, in the same
    * statement as the token it describes: the two cannot disagree, so a row that survives a
    * crash is either wholly rotated or wholly un-rotated.
    *
    * `acr` is `COALESCE`d like [[storeStepUp]]'s optional fields rather than written blindly. A
    * refresh does not lower assurance and the token response carries no ACR, so `None` means
    * "the exchange said nothing about it" -- writing it through would silently demote a stepped
    * -up session to its login assurance and have the driver step it up again after a restart.
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

  /** Deferred write path -- `access_expires_at` only (§7.5, less `acr`; see [[SessionTouch]]).
    *
    * The column only ever moves forward here. A touch is queued before it is applied, so one
    * queued ahead of a rotation or a step-up can reach the table after it, and a plain
    * assignment would then put a stale expiry back over the fresh one that critical write just
    * persisted. For a web session that column is [[listLive]]'s liveness boundary, so the
    * regression would discard a session that is still perfectly resumable.
    */
  def touchAll(touches: Chunk[SessionTouch]): Task[Unit]

  /** Removes a session that was logged out, expired, or retired by the recovery path above. */
  def delete(id: Long): Task[Unit]
