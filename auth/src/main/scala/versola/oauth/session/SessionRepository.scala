package versola.oauth.session

import versola.oauth.session.model.{SessionId, SessionRecord}
import zio.*

trait SessionRepository:
  def create(id: SessionId, record: SessionRecord, ttl: Duration): Task[Unit]

