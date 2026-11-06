package versola.oauth.session

import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{
  PublicSessionId,
  RefreshTokenRecord,
  SessionId,
  SessionInfo,
  SessionRecord,
  SessionUnderUserAgent,
  UserAgentDetails,
  UserAgentId,
  UserAgentRecord,
  UserDevice,
}
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Secret, SecurityService}
import zio.{Duration, Task, ZLayer}

trait SessionService:
  def find(rawId: SessionId): Task[Option[SessionInfo]]
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

  /** Atomically invalidates the session resolved by either its public id or raw id,
   *  returning the session that was invalidated so callers (e.g. logout) can use its
   *  data without a separate lookup. */
  def invalidate(id: Either[PublicSessionId, SessionId]): Task[Option[SessionInfo]]

  /** Invalidates a public session id only when it belongs to the supplied user. */
  def invalidateForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean]

  /** Registers a relying party as logged-in on this session, so it can be notified on
   *  front/back-channel logout. Idempotent: a no-op if the client is already registered. */
  def registerClient(id: MAC.Of[SessionId], clientId: ClientId): Task[Unit]

  /** Lists all active sessions of a user, one per user agent (device/browser) that
   *  created them, enriched with its details, so callers (e.g. controllers) never
   *  need to query [[UserAgentRepository]] themselves. */
  def listByUser(userId: UserId): Task[List[SessionUnderUserAgent]]

  /** Every remembered user agent of a user with the live session under it, if any -- the
   *  signed-out ones carry the ids of their ended sessions instead. A live session whose
   *  user agent row is gone is listed with `userAgent = None`. */
  def listDevicesByUser(userId: UserId): Task[List[UserDevice]]

  /** Forgets one of the user's user agents together with its sessions and their refresh tokens
   *  (see [[UserAgentRepository.forget]]). `false` when it is not this user's. */
  def forgetUserAgent(userId: UserId, userAgentId: UserAgentId): Task[Boolean]

  /** Invalidates every active session of a user (admin-panel force-logout), returning
   *  each session's record so callers (e.g. [[versola.oauth.logout.LogoutService]]) can
   *  fan out back-channel logout to its participating clients without a separate lookup. */
  def invalidateAllByUser(userId: UserId): Task[List[SessionRecord]]

  /** Every currently-live refresh token issued to a user, for admin-panel display. Not
   *  derived from [[listByUser]]'s sessions: a refresh token's expiry is fixed at issuance
   *  and independent of a session's, so a token routinely outlives the session it
   *  was issued under and must still be listed once that session is gone. */
  def listRefreshTokensByUser(userId: UserId): Task[List[RefreshTokenRecord]]

  /** Revokes the offline access one client holds for a user (admin-panel action): every live
   *  refresh token the client was issued for them expires now. The user's sessions stay. */
  def revokeRefreshTokensByUserAndClient(userId: UserId, clientId: ClientId): Task[Int]

object SessionService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      repository: SessionRepository,
      securityService: SecurityService,
      config: CoreConfig,
      userAgentRepository: UserAgentRepository,
  ) extends SessionService:

    override def find(rawId: SessionId): Task[Option[SessionInfo]] =
      for
        mac <- macOf(rawId)
        opt <- repository.findSession(mac)
      yield opt.map(SessionInfo(mac, _))

    private def macOf(rawId: SessionId): Task[MAC.Of[SessionId]] =
      securityService.mac(Secret(rawId), config.security.sessionsSecret)

    override def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit] =
      repository.prolongIdle(id, idleTtl)

    override def registerClient(id: MAC.Of[SessionId], clientId: ClientId): Task[Unit] =
      repository.registerClient(id, clientId)

    override def listByUser(userId: UserId): Task[List[SessionUnderUserAgent]] =
      liveSessions(userId).map(_._1)

    /** The live sessions with their user agents, and every remembered user agent of the user. */
    private def liveSessions(userId: UserId): Task[(List[SessionUnderUserAgent], List[UserAgentRecord])] =
      for
        stored <- repository.findByUserId(userId)
        agents <- userAgentRepository.findByUserId(userId)
        agentById = agents.map(agent => agent.id -> agent).toMap
      yield
        val sessions = stored.map: stored =>
          val session = stored.record
          val agent = agentById.get(session.userAgentId)
          val details = agent.fold(UserAgentDetails.parse(None))(a => UserAgentDetails(a.platform, a.os, a.browser, a.version))
          SessionUnderUserAgent(
            publicId = session.publicId,
            clients = session.clients,
            createdAt = session.createdAt,
            platform = details.platform,
            os = details.os,
            browser = details.browser,
            version = details.version,
            expiresAt = session.expiresAt,
            idleExpiresAt = stored.idleExpiresAt,
            userAgent = agent,
          )
        (sessions, agents)

    override def listDevicesByUser(userId: UserId): Task[List[UserDevice]] =
      for
        (sessions, agents) <- liveSessions(userId)
        ended <- repository.findEndedByUserId(userId)
      yield
        val signedIn = sessions.map: session =>
          UserDevice(userAgent = session.userAgent, session = Some(session), endedSessionIds = Nil)
        val signedOut = agents.filterNot(agent => sessions.exists(_.userAgent.exists(_.id == agent.id))).map: agent =>
          UserDevice(
            userAgent = Some(agent),
            session = None,
            endedSessionIds = ended.filter(_.userAgentId == agent.id).map(_.publicId),
          )
        // Signed-in devices lead, newest session first; the signed-out ones follow, most recently remembered first.
        signedIn ++ signedOut

    override def forgetUserAgent(userId: UserId, userAgentId: UserAgentId): Task[Boolean] =
      userAgentRepository.forget(userAgentId, userId)

    override def invalidate(id: Either[PublicSessionId, SessionId]): Task[Option[SessionInfo]] =
      id match
        case Left(publicId) =>
          repository.invalidateByPublicId(publicId).map(_.map(SessionInfo(_, _)))
        case Right(rawId) =>
          for
            mac <- macOf(rawId)
            record <- repository.invalidate(mac)
          yield record.map(SessionInfo(mac, _))

    override def invalidateForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean] =
      repository.invalidateByPublicIdForUser(publicId, userId)

    override def invalidateAllByUser(userId: UserId): Task[List[SessionRecord]] =
      repository.invalidateByUserId(userId)

    override def listRefreshTokensByUser(userId: UserId): Task[List[RefreshTokenRecord]] =
      repository.findRefreshTokensByUserId(userId)

    override def revokeRefreshTokensByUserAndClient(userId: UserId, clientId: ClientId): Task[Int] =
      repository.revokeRefreshTokensByUserAndClient(userId, clientId)
