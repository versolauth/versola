package versola.edge

import versola.edge.model.{EdgeSessionId, EdgeTokenRecord}
import versola.util.MAC
import zio.Task

trait EdgeTokenRepository:
  def create(record: EdgeTokenRecord): Task[Unit]

  def findBySessionId(sessionId: MAC.Of[EdgeSessionId]): Task[Option[EdgeTokenRecord]]

  def findByAccessTokenHash(accessTokenHash: MAC): Task[Option[EdgeTokenRecord]]

  def delete(sessionId: MAC.Of[EdgeSessionId]): Task[Unit]

