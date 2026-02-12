package versola.edge

import versola.edge.model.{EdgeSession, EdgeSessionId}
import versola.oauth.client.model.ClientId
import versola.util.MAC
import zio.{Duration, Task}

trait EdgeSessionRepository:
  def create(
      id: MAC.Of[EdgeSessionId],
      session: EdgeSession,
      ttl: Duration,
  ): Task[Unit]

  def find(id: MAC.Of[EdgeSessionId]): Task[Option[EdgeSession]]

  def findByClientId(clientId: ClientId): Task[List[EdgeSession]]

  def delete(id: MAC.Of[EdgeSessionId]): Task[Unit]

  def deleteByClientId(clientId: ClientId): Task[Unit]

