package versola.loadgen.scenario

import versola.loadgen.config.{BusinessActionConfig, SessionConfig}
import versola.loadgen.model.*
import versola.loadgen.protocol.*
import versola.loadgen.scheduler.{ActionCount, RandomSource, ThinkTimeTable}
import versola.loadgen.store.{DeferredUpdate, DeviceSessionRepository, SessionTouch, WriteBehindBuffer}
import zio.{Clock, IO, ZIO}

import java.time.Instant

/** The per-session state machine of design doc §2.3, as dev spec §7.4 requires it to behave.
  *
  * {{{
  * arrival
  *   +- live credential?  yes -> refresh (§8.5)      no -> full login (§8.1-8.4)
  *   +- N actions (§8.6), think time between them
  *   |    +- one of them is an L2 payment  -> StepUpRequired -> step up, replay the action once
  *   |    +- 403 / 401                     -> counted, the session carries on
  *   +- the session outlives its access token -> one extra refresh
  *   +- explicit logout, or the row is simply left for the next arrival
  * }}}
  *
  * **Outcomes are not failures.** `StepUpRequired`, `Forbidden` and `Unauthorized` each have a
  * branch here; none of them ends the session and none of them is reported as an error. That
  * split is made once, in [[versola.loadgen.metrics.StepOutcome]], and honoured here by having a
  * `case` for each -- a `catchAll` that treated the error channel as failure would put a third of
  * the campaign's planned traffic in the error budget, which dev spec §4 calls "the most common
  * way a load report becomes unreadable".
  *
  * **What is not retried.** Nothing. A hop that fails ends the session, the row is left for the
  * next arrival to resume or retire, and the user loses at most this one session. Retrying is how
  * a refresh token gets presented twice (§7.4) and how a driver's own recovery becomes load the
  * plan never asked for.
  *
  * One arrival is one session, and the caller holds the user busy for its whole length
  * ([[BusyUsers]]) -- which is what makes "one virtual user has at most one in-flight operation"
  * (§7.1) true of a multi-hop session and not merely of a single request.
  */
final class SessionRunner(
    mobile: MobileFlows,
    web: WebFlows,
    sessions: DeviceSessionRepository,
    buffer: WriteBehindBuffer,
    actions: BusinessActions,
    ids: SessionIds,
    thinkTime: ThinkTimeTable,
    clients: ScenarioClients,
    config: SessionConfig,
):
  import SessionRunner.*

  /** One arrival, end to end. The `random` is this fiber's own split of the campaign seed:
    * [[RandomSource]] is single-owner by design, so sharing one across session fibers would both
    * race and destroy the replayability the seed exists for.
    */
  def run(user: VirtualUser, random: RandomSource): IO[ProtocolError, Unit] =
    for
      resumable <- liveSession(user)
      plan = SessionPlan.draw(config, user.platform, random)
      started <- start(user, resumable, plan, random)
      _ <- ZIO.foreachDiscard(started)(session => act(user, session, plan, random, 0))
    yield ()

  /** §2.3's first branch. A resume that retires the session falls through to a full login rather
    * than ending the arrival: §7.4 step 5 says so in as many words ("mark session dead ... force
    * full login"), and it is also what a real app does -- the user opens it, the token is gone,
    * they see the login screen.
    */
  private def start(
      user: VirtualUser,
      resumable: Option[DeviceSession],
      plan: SessionPlan,
      random: RandomSource,
  ): IO[ProtocolError, Option[RunningSession]] =
    resumable match
      case Some(existing) if !SessionPlan.startsWithFullLogin(config, user.platform, true, random) =>
        resume(user, existing).flatMap:
          case Some(running) => ZIO.some(running)
          case None => login(user)
      case _ => login(user)

  /** The one session a user may resume, if it has one.
    *
    * Rows left mid-rotation are excluded by the repository itself, and the expiry filter is the
    * same `COALESCE` [[DeviceSessionRepository.listLive]] uses -- a mobile session lives as long
    * as its refresh token, a web one as long as its cookie. Taking the newest rather than the
    * first is deliberate: a user with several device sessions (§5's `population.classes` give
    * heavy users four) should resume the one most likely still to be honoured.
    */
  private def liveSession(user: VirtualUser): IO[ProtocolError, Option[DeviceSession]] =
    for
      now <- Clock.instant
      rows <- sessions.listByUser(user.id).mapError(storeFailure("session list"))
    yield rows.filter(row => !row.rotationInFlight && liveAt(row, now)).maxByOption(expiryOf)

  private def resume(user: VirtualUser, existing: DeviceSession): IO[ProtocolError, Option[RunningSession]] =
    existing.kind match
      // §8.4 has no refresh of its own: edge renews behind the cookie, so resuming a web session
      // is simply using the cookie the row holds and letting the next action rotate it.
      case SessionKind.WebCookie =>
        ZIO.succeed(existing.edgeCookie.map(cookie => RunningSession.web(existing, cookie)))
      case SessionKind.MobileToken =>
        for
          outcome <- RefreshDiscipline.refresh(existing, mobileCreds(user), mobile, sessions, config.refreshTokenTtl)
          now <- Clock.instant
          running <- outcome match
            case RefreshOutcome.Rotated(tokens, _) => ZIO.some(RunningSession.mobile(existing, tokens, now))
            case RefreshOutcome.Retired(reason) => retire(existing.id, reason).as(None)
        yield running

  private def login(user: VirtualUser): IO[ProtocolError, Option[RunningSession]] =
    user.platform match
      case Platform.Web => webLogin(user, None).map(Some(_))
      case Platform.Mobile => mobileLogin(user, None).map(Some(_))

  private def mobileLogin(user: VirtualUser, acrValues: Option[List[String]]): IO[ProtocolError, RunningSession] =
    val clientId = clients.mobileClientFor(user.credential)
    val request = LoginRequest(Some(clientId), clients.scope, acrValues, None)
    for
      credentials <- loginCredentials(user)
      result <- user.credential match
        case CredentialKind.Otp => mobile.mobileOtp(request, user.phone)
        case CredentialKind.OtpPassword =>
          credentials match
            case Credentials.PhoneOtpPassword(phone, password) => mobile.mobileOtpPassword(request, phone, password)
            case _ => ZIO.fail(ProtocolError.Misconfigured(s"user ${user.id} has no password to log in with"))
        case CredentialKind.Passkey =>
          credentials match
            case Credentials.Passkey(credential, sutUserId) => mobile.mobilePasskey(request, credential, sutUserId)
            case _ => ZIO.fail(ProtocolError.Misconfigured(s"user ${user.id} has no passkey to log in with"))
      (tokens, ssoSession) = result
      now <- Clock.instant
      id <- ids.next
      row = mobileRow(id, user, clientId, tokens, ssoSession, acrValues, now)
      _ <- sessions.insert(row).mapError(storeFailure("new mobile session"))
    yield RunningSession.mobile(row, tokens, now)

  private def webLogin(user: VirtualUser, acrValues: Option[List[String]]): IO[ProtocolError, RunningSession] =
    for
      credentials <- loginCredentials(user)
      result <- web.webOtp(WebLoginRequest(clients.webPreset, acrValues), credentials)
      (cookie, ssoSession) = result
      now <- Clock.instant
      expiresAt <- cookieExpiry(cookie, now)
      id <- ids.next
      row = DeviceSession.webCookie(
        id = id,
        userId = user.id,
        clientId = clients.webPreset.value,
        cookie = cookie.session,
        ssoSession = ssoSession,
        accessExpiresAt = expiresAt,
        acr = acrValues.flatMap(_.headOption),
        authTime = now,
        shard = user.shard,
      )
      _ <- sessions.insert(row).mapError(storeFailure("new web session"))
    yield RunningSession.web(row, cookie.session)

  /** The action loop. Recursive rather than a fold so that the running session -- which a cookie
    * rotation, a refresh or a step-up all replace -- is carried forward by value, and so that the
    * think time sits between two actions rather than after the last one.
    */
  private def act(
      user: VirtualUser,
      session: RunningSession,
      plan: SessionPlan,
      random: RandomSource,
      index: Int,
  ): IO[ProtocolError, Unit] =
    if index >= plan.actionCount then finish(user, session, plan)
    else
      for
        refreshed <- refreshIfStale(user, session, plan)
        call <- ZIO.fromEither(actions.call(chosen(plan, index, random), user.id)).mapError(detail => ProtocolError.Misconfigured(detail))
        next <- perform(user, refreshed, call, random)
        _ <- ZIO.sleep(thinkTime.sample(random)).when(index + 1 < plan.actionCount)
        _ <- next match
          case Some(running) => act(user, running, plan, random, index + 1)
          // The session lost its credential mid-run -- a rejected refresh behind a 401, or a
          // step-up that could not complete. The row is already retired; the user's next arrival
          // logs in again rather than this one starting over, which would be a session the
          // arrival process never scheduled.
          case None => ZIO.unit
      yield ()

  /** Which of the ten actions this slot is.
    *
    * Slot 0 is the app's own opening call (design doc §3, [[ActionCount.mandatoryActions]]); the
    * slot the plan drew for a payment takes an action that requires an ACR, which is what makes
    * the SUT demand the step-up rather than the driver deciding to perform one; everything else
    * is the ordinary weighted draw.
    */
  private def chosen(plan: SessionPlan, index: Int, random: RandomSource): BusinessActionConfig =
    if index < ActionCount.mandatoryActions then actions.opening
    else if plan.paymentAction.contains(index) then actions.pickStepUp(random).getOrElse(actions.pickOrdinary(random))
    else actions.pickOrdinary(random)

  /** §2.3's "session longer than the access-token TTL -> extra refresh", which is a property of
    * the clock and not of the action count: a session whose think times happened to be short does
    * not reach the TTL and does not refresh, even though the plan allowed it to.
    *
    * Web sessions are absent on purpose -- edge refreshes behind the cookie and hands back a
    * rotated one, so there is no token here to renew and forcing a re-login would be inventing
    * traffic.
    */
  private def refreshIfStale(user: VirtualUser, session: RunningSession, plan: SessionPlan): IO[ProtocolError, RunningSession] =
    if !plan.extraRefresh || session.kind != SessionKind.MobileToken then ZIO.succeed(session)
    else
      Clock.instant.flatMap: now =>
        if now.isBefore(session.accessExpiresAt) then ZIO.succeed(session)
        else renew(user, session).map(_.getOrElse(session))

  /** A refresh on a session already in flight: the row is re-read because [[RefreshDiscipline]]
    * works from the persisted credential, which is the only copy §7.4's generation guard is
    * stated against.
    */
  private def renew(user: VirtualUser, session: RunningSession): IO[ProtocolError, Option[RunningSession]] =
    for
      row <- sessions.find(session.id).mapError(storeFailure("session reload"))
      outcome <- ZIO.foreach(row)(current =>
        RefreshDiscipline.refresh(current, mobileCreds(user), mobile, sessions, config.refreshTokenTtl),
      )
      now <- Clock.instant
      renewed <- (row, outcome) match
        case (Some(current), Some(RefreshOutcome.Rotated(tokens, _))) => ZIO.some(RunningSession.mobile(current, tokens, now))
        case (Some(current), Some(RefreshOutcome.Retired(reason))) => retire(current.id, reason).as(None)
        case _ => ZIO.none
    yield renewed

  /** §8.6 plus the three branches §7.4 and §4 require of its outcome. `None` means the session
    * cannot continue; it is not an error, and the row has already been dealt with.
    */
  private def perform(
      user: VirtualUser,
      session: RunningSession,
      call: ActionCall,
      random: RandomSource,
  ): IO[ProtocolError, Option[RunningSession]] =
    callAction(session, call).either.flatMap:
      case Right(running) => ZIO.some(running)
      case Left(ProtocolError.StepUpRequired(acrValues, _)) => stepUp(user, session, call, acrValues)
      // Expected for `retail-basic` on actions its role does not cover (design doc §3). Counted
      // by the observer, charged to no budget, and the session simply goes on to its next action
      // -- a real app shows a "not permitted" and the user carries on.
      case Left(ProtocolError.Forbidden(_)) => ZIO.some(session)
      // Expected once the access token's TTL has elapsed (§8.6). One renewal, then on; the action
      // is not replayed, because what the campaign measures here is the 401 and the refresh it
      // provokes, and a replay would double-count the action against its own weight.
      case Left(ProtocolError.Unauthorized(_)) => renewAfterUnauthorized(user, session)
      case Left(error) => ZIO.fail(error)

  private def callAction(session: RunningSession, call: ActionCall): IO[ProtocolError, RunningSession] =
    session.credential match
      case EdgeCredential.Cookie(cookie) =>
        web.businessAction(cookie, call).flatMap: (outcome, next) =>
          adoptRotation(session, outcome, next)
      case bearer: EdgeCredential.Bearer =>
        mobile.businessAction(bearer, call).as(session)

  /** §8.4's adoption rule, discharged on the critical path: edge rotates `EDGE_SESSION` behind a
    * refresh, and a row still holding the superseded value resumes a session the SUT has moved
    * on from. Written only when the value actually changed, so an ordinary action costs no store
    * round trip.
    */
  private def adoptRotation(
      session: RunningSession,
      outcome: ActionOutcome,
      next: EdgeSession,
  ): IO[ProtocolError, RunningSession] =
    outcome.rotatedSession match
      case None => ZIO.succeed(session)
      case Some(rotated) =>
        for
          now <- Clock.instant
          expiresAt <- cookieExpiry(rotated, now)
          _ <- sessions.storeEdgeCookie(session.id, next, expiresAt).mapError(storeFailure("rotated edge cookie"))
        yield session.withCookie(next, expiresAt)

  private def renewAfterUnauthorized(user: VirtualUser, session: RunningSession): IO[ProtocolError, Option[RunningSession]] =
    session.kind match
      case SessionKind.MobileToken => renew(user, session)
      // On the cookie path a 401 means edge could not refresh either, so the session is finished
      // (see `WebFlows.businessAction`). The row is retired here rather than left behind: it
      // would fail `listLive`'s expiry test eventually, but until then it is a session the next
      // arrival would resume into the same 401.
      case SessionKind.WebCookie => sessions.delete(session.id).mapError(storeFailure("dead web session")).as(None)

  /** §7.4's step-up: re-authenticate at the requested assurance level on the SSO session this one
    * already holds, persist what that produced, and **replay the original action once**.
    *
    * The replay is not a retry of a failure -- the 401 was the SUT telling the driver what to do
    * next, and it has already been counted as the planned outcome it is. Exactly once: a second
    * `StepUpRequired` after a completed step-up means the ACR vocabulary and the endpoint's
    * `stepUpAcr` disagree (`CampaignBlueprint.challengeSettings`), and looping on it would turn a
    * provisioning mistake into unbounded load on auth's conversation path.
    */
  private def stepUp(
      user: VirtualUser,
      session: RunningSession,
      call: ActionCall,
      acrValues: List[String],
  ): IO[ProtocolError, Option[RunningSession]] =
    for
      steppedUp <- session.kind match
        case SessionKind.MobileToken => mobileStepUp(user, session, acrValues)
        case SessionKind.WebCookie => webStepUp(user, session, acrValues)
      replayed <- ZIO.foreach(steppedUp)(running => callAction(running, call).either)
    yield replayed match
      case Some(Right(running)) => Some(running)
      // The action refused again, or refused differently. Counted by the observer either way; the
      // session keeps whatever the step-up produced and moves on to its next action.
      case Some(Left(_)) => steppedUp
      case None => None

  private def mobileStepUp(
      user: VirtualUser,
      session: RunningSession,
      acrValues: List[String],
  ): IO[ProtocolError, Option[RunningSession]] =
    val clientId = clients.mobileClientFor(user.credential)
    for
      credentials <- loginCredentials(user)
      result <- mobile.stepUp(LoginRequest(Some(clientId), clients.scope, Some(acrValues), session.ssoSession), credentials)
      (tokens, ssoSession) = result
      now <- Clock.instant
      accessExpiresAt = now.plusSeconds(tokens.expiresInSeconds)
      _ <- sessions
        .storeStepUp(
          id = session.id,
          acr = acrValues.mkString(" "),
          authTime = now,
          accessExpiresAt = accessExpiresAt,
          refreshToken = tokens.refreshToken,
          refreshExpiresAt = tokens.refreshToken.map(_ => now.plusSeconds(config.refreshTokenTtl.toSeconds)),
          ssoSession = ssoSession,
        )
        .mapError(storeFailure("step-up"))
    yield Some(
      session.copy(
        credential = EdgeCredential.Bearer(tokens.accessToken),
        refreshToken = tokens.refreshToken.orElse(session.refreshToken),
        idToken = tokens.idToken.orElse(session.idToken),
        ssoSession = ssoSession.orElse(session.ssoSession),
        acr = Some(acrValues.mkString(" ")),
        accessExpiresAt = accessExpiresAt,
      ),
    )

  /** The cookie path's step-up produces a new `EDGE_SESSION`, so it takes two critical writes and
    * not one: [[DeviceSessionRepository.storeStepUp]] has no cookie column -- a step-up on the
    * mobile path replaces a token pair -- and a row that recorded the new assurance level without
    * the credential it came with would claim an L2 session and present the L1 cookie.
    */
  private def webStepUp(
      user: VirtualUser,
      session: RunningSession,
      acrValues: List[String],
  ): IO[ProtocolError, Option[RunningSession]] =
    for
      credentials <- loginCredentials(user)
      result <- web.stepUp(WebLoginRequest(clients.webPreset, Some(acrValues)), credentials)
      (cookie, ssoSession) = result
      now <- Clock.instant
      expiresAt <- cookieExpiry(cookie, now)
      _ <- sessions
        .storeStepUp(
          id = session.id,
          acr = acrValues.mkString(" "),
          authTime = now,
          accessExpiresAt = expiresAt,
          refreshToken = None,
          refreshExpiresAt = None,
          ssoSession = ssoSession,
        )
        .mapError(storeFailure("web step-up"))
      _ <- sessions.storeEdgeCookie(session.id, cookie.session, expiresAt).mapError(storeFailure("stepped-up edge cookie"))
    yield Some(
      session.copy(
        credential = EdgeCredential.Cookie(cookie.session),
        ssoSession = ssoSession.orElse(session.ssoSession),
        acr = Some(acrValues.mkString(" ")),
        accessExpiresAt = expiresAt,
      ),
    )

  /** The end of a session: an explicit logout if the plan drew one, and the deferred bookkeeping
    * either way.
    *
    * A session that does not log out is simply left in the table -- that is what makes the next
    * arrival a refresh rather than a login, and it is where §2.3's 96.7% comes from. The two
    * touches go through the write-behind buffer because §7.5 puts them there: neither is read
    * back to make a decision during the run, so losing one to a full queue costs a user being
    * picked slightly out of turn, not a credential.
    */
  private def finish(user: VirtualUser, session: RunningSession, plan: SessionPlan): IO[ProtocolError, Unit] =
    for
      _ <- ZIO.when(plan.logout)(logout(session))
      now <- Clock.instant
      _ <- buffer.enqueue(DeferredUpdate.UserSeen(user.id, now))
      _ <- buffer
        .enqueue(DeferredUpdate.SessionTouched(SessionTouch(session.id, session.accessExpiresAt)))
        .unless(plan.logout)
    yield ()

  /** A logout that the SUT accepted but whose row survived would leave a session the next arrival
    * resumes into a 401, so the delete is awaited. A logout the SUT refused is a protocol error
    * and propagates: the row stays, and the session it describes is still live.
    */
  private def logout(session: RunningSession): IO[ProtocolError, Unit] =
    val ended = (session.kind, session.cookie, session.ssoSession, session.idToken) match
      case (SessionKind.WebCookie, Some(cookie), Some(ssoSession), _) =>
        web.logout(WebLoginRequest(clients.webPreset, None), cookie, ssoSession)
      case (SessionKind.MobileToken, _, _, Some(idToken)) => mobile.logout(idToken)
      // Nothing to present: a mobile session with no id token (a resumed one whose refresh
      // response carried none), or a web session with no SSO session. Dropping the row is still
      // right -- the scenario said this session ends here -- but no hop is made, so nothing is
      // measured that did not happen.
      case _ => ZIO.unit
    ended *> sessions.delete(session.id).mapError(storeFailure("logged-out session"))

  /** A session the refresh discipline could not carry forward. The row goes, whatever the reason:
    * a retired session left in the table is one the next arrival resumes into the same dead end,
    * and the [[RefreshDiscipline.refresh]] paths that already deleted it are idempotent here.
    */
  private def retire(id: Long, reason: RetirementReason): IO[ProtocolError, Unit] =
    ZIO.logDebug(s"Retiring session $id: ${reason.detail}") *>
      sessions.delete(id).mapError(storeFailure("retired session"))

  private def loginCredentials(user: VirtualUser): IO[ProtocolError, Credentials] =
    user.credential match
      case CredentialKind.Otp => ZIO.succeed(Credentials.PhoneOtp(user.phone))
      case CredentialKind.OtpPassword =>
        ZIO
          .fromOption(user.password)
          .mapBoth(_ => ProtocolError.Misconfigured(s"user ${user.id} is an otp-password user with no password"), Credentials.PhoneOtpPassword(user.phone, _))
      case CredentialKind.Passkey =>
        for
          credentialId <- required(user.passkeyCredId, s"user ${user.id} is a passkey user with no credential id")
          key <- required(user.passkeyKey, s"user ${user.id} is a passkey user with no private key")
          sutUserId <- required(user.sutUserId, s"user ${user.id} is a passkey user that was never registered")
          credential <- SoftAuthenticator.restore(credentialId, key)
        yield Credentials.Passkey(credential, sutUserId)

  /** The three `mobile-*` clients are public and authenticate with PKCE alone (§2.2), so there is
    * no secret to look up -- [[versola.loadgen.protocol.HttpAuthClient]] names the client in the
    * body rather than sending HTTP Basic.
    */
  private def mobileCreds(user: VirtualUser): ClientCreds =
    ClientCreds(clients.mobileClientFor(user.credential), None)

  private def mobileRow(
      id: Long,
      user: VirtualUser,
      clientId: String,
      tokens: Tokens,
      ssoSession: Option[SsoSession],
      acrValues: Option[List[String]],
      now: Instant,
  ): DeviceSession =
    DeviceSession(
      id = id,
      userId = user.id,
      kind = SessionKind.MobileToken,
      clientId = clientId,
      refreshToken = tokens.refreshToken,
      edgeCookie = None,
      ssoSession = ssoSession,
      accessExpiresAt = Some(now.plusSeconds(tokens.expiresInSeconds)),
      refreshExpiresAt = tokens.refreshToken.map(_ => now.plusSeconds(config.refreshTokenTtl.toSeconds)),
      acr = acrValues.flatMap(_.headOption),
      authTime = Some(now),
      // A fresh session has rotated nothing, so both generations start equal -- which is what
      // keeps it out of `listInterruptedRotations` and inside `listLive` (§7.4).
      generation = 0,
      refreshGeneration = 0,
      shard = user.shard,
    )

  private def cookieExpiry(cookie: EdgeCookie, now: Instant): IO[ProtocolError, Instant] =
    ZIO
      .fromOption(cookie.maxAge)
      .mapBoth(
        _ => ProtocolError.MalformedResponse(completeEndpoint, "EDGE_SESSION without a Max-Age"),
        maxAge => now.plusSeconds(maxAge.toSeconds),
      )

  private def required[A](value: Option[A], detail: String): IO[ProtocolError, A] =
    ZIO.fromOption(value).orElseFail(ProtocolError.Misconfigured(detail))

  private def liveAt(row: DeviceSession, now: Instant): Boolean =
    expiryOf(row).isAfter(now)

  private def expiryOf(row: DeviceSession): Instant =
    row.refreshExpiresAt.orElse(row.accessExpiresAt).getOrElse(Instant.MIN)

  private def storeFailure(what: String)(cause: Throwable): ProtocolError =
    ProtocolError.Misconfigured(s"the emulator's store could not write the $what: ${cause.getMessage}")

object SessionRunner:
  private val completeEndpoint = "/complete"

  /** A session as it is being run: the row's identity plus the credentials in hand, which a
    * rotation, a refresh or a step-up each replace.
    *
    * Not a [[DeviceSession]], deliberately. That type is the persisted shape, and the access
    * token -- the thing every action is actually made with -- is not in it and must not be: §6's
    * columns hold what makes a session *resumable*, and a 15-minute access token never survives
    * long enough to be worth a write. Keeping the two apart is also what stops a caller from
    * persisting an in-memory credential by accident.
    */
  private[scenario] final case class RunningSession(
      id: Long,
      kind: SessionKind,
      credential: EdgeCredential,
      refreshToken: Option[RefreshToken],
      ssoSession: Option[SsoSession],
      idToken: Option[IdToken],
      acr: Option[String],
      accessExpiresAt: Instant,
  ):
    def cookie: Option[EdgeSession] =
      credential match
        case EdgeCredential.Cookie(session) => Some(session)
        case _ => None

    def withCookie(session: EdgeSession, expiresAt: Instant): RunningSession =
      copy(credential = EdgeCredential.Cookie(session), accessExpiresAt = expiresAt)

  private[scenario] object RunningSession:
    def mobile(row: DeviceSession, tokens: Tokens, now: Instant): RunningSession =
      RunningSession(
        id = row.id,
        kind = SessionKind.MobileToken,
        credential = EdgeCredential.Bearer(tokens.accessToken),
        refreshToken = tokens.refreshToken.orElse(row.refreshToken),
        ssoSession = row.ssoSession,
        idToken = tokens.idToken,
        acr = row.acr,
        accessExpiresAt = now.plusSeconds(tokens.expiresInSeconds),
      )

    /** A resumed web session takes its expiry from the row, which is the cookie's own `Max-Age`
      * as edge last set it -- the only expiry a cookie session has (§8.4).
      */
    def web(row: DeviceSession, cookie: EdgeSession): RunningSession =
      RunningSession(
        id = row.id,
        kind = SessionKind.WebCookie,
        credential = EdgeCredential.Cookie(cookie),
        refreshToken = None,
        ssoSession = row.ssoSession,
        idToken = None,
        acr = row.acr,
        accessExpiresAt = row.accessExpiresAt.getOrElse(Instant.MIN),
      )
