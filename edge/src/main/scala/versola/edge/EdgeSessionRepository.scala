package versola.edge

import versola.edge.model.{EdgeSession, EdgeSessionId}
import versola.util.MAC
import zio.{Duration, Task}

trait EdgeSessionRepository:
  def create(
      id: MAC.Of[EdgeSessionId],
      session: EdgeSession,
      ttl: Duration,
  ): Task[Unit]

  def find(id: MAC.Of[EdgeSessionId]): Task[Option[EdgeSession]]

  def delete(id: MAC.Of[EdgeSessionId]): Task[Unit]

