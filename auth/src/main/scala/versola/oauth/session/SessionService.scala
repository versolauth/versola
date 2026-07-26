package versola.oauth.session

import versola.oauth.session.model.{SessionId, SessionInfo}
import versola.util.{CoreConfig, MAC, Secret, SecurityService}
import zio.{Duration, Task, ZLayer}

trait SessionService:
  def find(rawId: SessionId): Task[Option[SessionInfo]]
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

object SessionService:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      repository: SessionRepository,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends SessionService:

    override def find(rawId: SessionId): Task[Option[SessionInfo]] =
      for
        mac <- securityService.mac(Secret(rawId), config.security.sessionsSecret)
        opt <- repository.findSession(mac)
      yield opt.map(SessionInfo(mac, _))

    override def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit] =
      repository.prolongIdle(id, idleTtl)
