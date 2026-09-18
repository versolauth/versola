package versola.loadgen.scenario

import versola.loadgen.model.DeviceSession
import versola.loadgen.protocol.*
import versola.loadgen.store.DeviceSessionRepository
import zio.{Clock, Duration, IO, UIO, ZIO}

/** How a refresh exchange ended, from the session's point of view.
  *
  * [[Retired]] is not a failure and carries no error: every one of its reasons is a state the
  * discipline is designed to produce rather than to avoid, and the session's answer to all of
  * them is the same -- stop using this row and log in again. Modelling them as failures would put
  * a planned outcome in the error channel, which is exactly the split dev spec §4 warns about.
  */
enum RefreshOutcome:
  case Rotated(tokens: Tokens, generation: Int)
  case Retired(reason: RetirementReason)

/** Why a session stopped being usable without anything having gone wrong with the SUT. */
enum RetirementReason(val detail: String):
  /** The row was deleted between the scenario reading it and the exchange -- a concurrent
    * recovery pass, or a logout on another arrival.
    */
  case SessionGone extends RetirementReason("the session row is gone")

  /** The SUT refused the token. The one reason that is also counted against the error budget
    * (`loadgen_refresh_rejected_total`, which must stay at ~0): §7.4 treats a rejection as the
    * emulator being wrong until proven otherwise.
    */
  case Rejected extends RetirementReason("the refresh token was rejected")

  /** The token exchange succeeded but the row had already moved on -- another bump landed while
    * this exchange was in flight, so the rotated token belongs to nobody. Retiring is the only
    * safe answer: writing it would resurrect a session the recovery path had retired, and using
    * it without writing it would leave a live token nothing persists.
    */
  case Superseded extends RetirementReason("the session was re-bumped while the exchange was in flight")

  /** A mobile session row with no refresh token in it. Nothing to exchange, so nothing to
    * replay; the user logs in again.
    */
  case NoCredential extends RetirementReason("the session holds no refresh token")

/** §7.4's refresh discipline, in the order the dev spec numbers it:
  *
  * {{{
  * 1. mark session busy (in-memory)                 -- BusyUsers, held by the caller
  * 2. persist intent: UPDATE ... generation + 1      -- immediate, awaited, before step 3
  * 3. POST /token grant_type=refresh_token
  * 4. on success: persist the new refresh token      -- immediate, awaited, before it is used
  * 5. on RefreshRejected: retire the session
  * 6. release busy                                   -- the caller's, on every path
  * }}}
  *
  * Step 2 before step 3 is the whole point, and it is the one ordering that cannot be relaxed
  * for performance: a driver that dies between them leaves a row whose `generation` moved but
  * whose `refresh_generation` did not, which [[DeviceSessionRepository.listInterruptedRotations]]
  * finds and [[SessionRecovery]] deletes. Bump *after* the exchange instead and that same crash
  * leaves a row indistinguishable from a settled one, the restarted driver resumes on a token the
  * SUT may already have rotated, and reuse detection reports a `refresh_rejected` that is the
  * emulator's fault and reads as the SUT's.
  *
  * The token is never presented twice. It is read once out of the row, exchanged once, and
  * replaced by [[DeviceSessionRepository.storeRotatedRefresh]] under the generation this call
  * owns -- so a retry does not exist here by construction rather than by policy. §7.4's
  * `loadgen_refresh_rejected` must stay at 0, and a retry is the shortest path to making it not.
  */
object RefreshDiscipline:

  /** @param refreshTokenTtl
    *   `session.refresh-token-ttl`, since the token response describes only the access token's
    *   lifetime. See [[versola.loadgen.config.SessionConfig.refreshTokenTtl]].
    * @param key
    *   the key this session's refresh token was bound to at login, on a DPoP run. It must be
    *   the same one across a driver restart, which is what
    *   [[versola.loadgen.protocol.DpopKeyPool]] guarantees and why it derives its keys rather
    *   than generating them -- a mismatch here is refused as `invalid_grant`, which is the
    *   rejection this whole discipline exists to keep at zero.
    */
  def refresh(
      session: DeviceSession,
      client: ClientCreds,
      flows: MobileFlows,
      sessions: DeviceSessionRepository,
      refreshTokenTtl: Duration,
      key: Option[DpopKey],
  ): IO[ProtocolError, RefreshOutcome] =
    session.refreshToken match
      case None => ZIO.succeed(RefreshOutcome.Retired(RetirementReason.NoCredential))
      case Some(token) =>
        bump(sessions, session.id).flatMap:
          case None => ZIO.succeed(RefreshOutcome.Retired(RetirementReason.SessionGone))
          case Some(generation) => exchange(session, token, generation, client, flows, sessions, refreshTokenTtl, key)

  private def bump(sessions: DeviceSessionRepository, id: Long): IO[ProtocolError, Option[Int]] =
    sessions.bumpGeneration(id).mapError(storeFailure("generation bump"))

  private def exchange(
      session: DeviceSession,
      token: RefreshToken,
      generation: Int,
      client: ClientCreds,
      flows: MobileFlows,
      sessions: DeviceSessionRepository,
      refreshTokenTtl: Duration,
      key: Option[DpopKey],
  ): IO[ProtocolError, RefreshOutcome] =
    flows.refresh(token, client, key).either.flatMap:
      // Step 5. The rejection has already been counted by the observer (§11's dedicated counter),
      // so what is left is to stop the session: the token in the row is spent either way, and
      // presenting it again is the reuse the discipline exists to make impossible.
      case Left(ProtocolError.RefreshRejected(_)) =>
        sessions.delete(session.id).mapError(storeFailure("retire")).as(RefreshOutcome.Retired(RetirementReason.Rejected))
      case Left(error) => ZIO.fail(error)
      case Right(tokens) => persist(session, tokens, generation, sessions, refreshTokenTtl)

  /** Step 4, awaited before the caller may use the access token beside it.
    *
    * A rotation with no new refresh token in the response is treated as a malformed response
    * rather than as a session that simply stops rotating: `offline_access` is on every client
    * (`CampaignBlueprint.scopes`) and the SUT rotates on every exchange, so the absence is the
    * emulator having asked for something other than what it thinks -- and carrying the old token
    * forward would present it a second time, which is precisely the reuse this whole file is
    * about.
    */
  private def persist(
      session: DeviceSession,
      tokens: Tokens,
      generation: Int,
      sessions: DeviceSessionRepository,
      refreshTokenTtl: Duration,
  ): IO[ProtocolError, RefreshOutcome] =
    tokens.refreshToken match
      case None => ZIO.fail(ProtocolError.MalformedResponse(tokenEndpoint, "a rotation that returned no refresh token"))
      case Some(rotated) =>
        for
          now <- Clock.instant
          wrote <- sessions
            .storeRotatedRefresh(
              id = session.id,
              expectedGeneration = generation,
              refreshToken = rotated,
              refreshExpiresAt = now.plusSeconds(refreshTokenTtl.toSeconds),
              accessExpiresAt = now.plusSeconds(tokens.expiresInSeconds),
              // The token response says nothing about assurance and a refresh cannot raise it, so
              // the stored value stands -- see `storeRotatedRefresh`'s COALESCE.
              acr = None,
              authTime = now,
            )
            .mapError(storeFailure("rotated refresh token"))
        yield if wrote then RefreshOutcome.Rotated(tokens, generation) else RefreshOutcome.Retired(RetirementReason.Superseded)

  /** The emulator's own store failing is not a measurement of the SUT, and it is not an error the
    * budget should absorb either -- it is the instrument losing the state its next decision rests
    * on. [[ProtocolError.Misconfigured]] is the channel §4 reserves for exactly that, and it
    * halts the campaign rather than being counted.
    */
  private def storeFailure(what: String)(cause: Throwable): ProtocolError =
    ProtocolError.Misconfigured(s"the emulator's store could not write the $what: ${cause.getMessage}")

  private val tokenEndpoint = "/token"
