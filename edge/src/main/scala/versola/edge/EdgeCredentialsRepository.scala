package versola.edge

import versola.edge.model.EdgeCredentials
import versola.oauth.client.model.ClientId
import zio.Task

trait EdgeCredentialsRepository:
  def find(clientId: ClientId): Task[Option[EdgeCredentials]]

  def getAll: Task[Map[ClientId, EdgeCredentials]]

  def create(credentials: EdgeCredentials): Task[Unit]

  def update(credentials: EdgeCredentials): Task[Unit]

  def delete(clientId: ClientId): Task[Unit]

