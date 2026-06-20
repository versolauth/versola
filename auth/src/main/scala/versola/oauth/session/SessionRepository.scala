package versola.oauth.session

import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{SessionId, SessionRecord, WithTtl}
import versola.user.model.UserId
import versola.util.MAC
import zio.*
import zio.prelude.These

trait SessionRepository:
  def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
  ): Task[Unit]

  def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  def findByUser(
      userId: UserId,
  ): Task[List[(MAC.Of[SessionId], SessionRecord)]]

  def invalidate(
      id: MAC.Of[SessionId],
  ): Task[Unit]
