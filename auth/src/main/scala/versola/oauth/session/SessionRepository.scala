package versola.oauth.session

import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.*

trait SessionRepository:
  def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
  ): Task[Unit]

  def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  def findByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  def invalidateByUserId(
      userId: UserId,
  ): Task[Unit]
