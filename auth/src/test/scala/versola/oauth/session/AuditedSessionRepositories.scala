package versola.oauth.session

import versola.audit.{AuditProbe, AuthAuditKinds}
import versola.oauth.client.model.ClientId
import versola.oauth.model.RefreshToken
import versola.oauth.session.model.*
import versola.user.model.UserId
import versola.util.MAC
import zio.{Duration, IO, Task}

// The repositories of sessions and refresh tokens as the specs that are about something else than the audit call them: each change is
// handed a fresh event, as the service hands one down. A call that gives its own event is the real one, which the specs of the events
// themselves make.
extension (repository: SessionRepository)
  def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
      priorSession: Option[PriorSession],
  ): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.LoginCompleted, Some(session.userId)).flatMap(repository.create(id, session, ttl, idleTtl, priorSession, _))

  def invalidateByUserId(userId: UserId): Task[List[SessionRecord]] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(repository.invalidateByUserId(userId, _))

  def revokeRefreshTokensByUserAndClient(userId: UserId, clientId: ClientId): Task[Int] =
    AuditProbe.event(AuthAuditKinds.TokenRevoked).flatMap(repository.revokeRefreshTokensByUserAndClient(userId, clientId, _))

  def invalidate(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(repository.invalidate(id, _))

  def invalidateByPublicId(publicId: PublicSessionId): Task[Option[(MAC.Of[SessionId], SessionRecord)]] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(repository.invalidateByPublicId(publicId, _))

  def invalidateByPublicIdForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(repository.invalidateByPublicIdForUser(publicId, userId, _))

  def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      previous: Option[MAC.Of[RefreshToken]],
      record: RefreshTokenRecord,
      idempotencyKey: Option[MAC],
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    AuditProbe.event(AuthAuditKinds.TokenIssued).flatMap(event =>
      repository.createRefreshToken(refreshToken, previous, record, idempotencyKey, Some(event)),
    )

  def revokeFamily(token: MAC.Of[RefreshToken], clientId: ClientId): Task[Option[RevokedFamily]] =
    AuditProbe.event(AuthAuditKinds.TokenReuseDetected).flatMap(repository.revokeFamily(token, clientId, _))

  def delete(token: MAC.Of[RefreshToken]): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.TokenRevoked).flatMap(repository.delete(token, _))

  def deleteByFamily(familyId: RefreshTokenFamilyId): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.TokenReuseDetected).flatMap(repository.deleteByFamily(familyId, _))

extension (repository: UserAgentRepository)
  def forget(id: UserAgentId, userId: UserId): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(repository.forget(id, userId, _))

extension (service: SessionService)
  def invalidate(id: Either[PublicSessionId, SessionId]): Task[Option[SessionInfo]] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(service.invalidate(id, _))

  def invalidateForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(service.invalidateForUser(publicId, userId, _))

  def invalidateAllByUser(userId: UserId): Task[List[SessionRecord]] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(service.invalidateAllByUser(userId, _))

  def forgetUserAgent(userId: UserId, userAgentId: UserAgentId): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.SessionEnded).flatMap(service.forgetUserAgent(userId, userAgentId, _))

  def revokeRefreshTokensByUserAndClient(userId: UserId, clientId: ClientId): Task[Int] =
    AuditProbe.event(AuthAuditKinds.TokenRevoked).flatMap(service.revokeRefreshTokensByUserAndClient(userId, clientId, _))
