package versola.oauth.session

import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionInfo}
import versola.util.{CoreConfig, MAC, Secret, SecurityService}
import zio.{Duration, Task, ZLayer}

trait SessionService:
  def find(rawId: SessionId): Task[Option[SessionInfo]]
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

  /** Atomically invalidates the session resolved by either its public id or raw id,
   *  returning the session that was invalidated so callers (e.g. logout) can use its
   *  data without a separate lookup. */
  def invalidate(id: Either[PublicSessionId, SessionId]): Task[Option[SessionInfo]]

  /** Registers a relying party as logged-in on this session, so it can be notified on
   *  front/back-channel logout. Idempotent: a no-op if the client is already registered. */
  def registerClient(id: MAC.Of[SessionId], clientId: ClientId): Task[Unit]

object SessionService:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      repository: SessionRepository,
      securityService: SecurityService,
      config: CoreConfig,
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

    override def invalidate(id: Either[PublicSessionId, SessionId]): Task[Option[SessionInfo]] =
      id match
        case Left(publicId) =>
          repository.invalidateByPublicId(publicId).map(_.map(SessionInfo(_, _)))
        case Right(rawId) =>
          for
            mac    <- macOf(rawId)
            record <- repository.invalidate(mac)
          yield record.map(SessionInfo(mac, _))