package versola.oauth.session

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{SessionId, SessionRecord, WithTtl}
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
