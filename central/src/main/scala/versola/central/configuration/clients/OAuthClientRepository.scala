package versola.central.configuration.clients

import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.*

trait OAuthClientRepository extends CacheSource[Vector[OAuthClientRecord]]:

  def getAll: Task[Vector[OAuthClientRecord]]

  def find(clientId: ClientId): Task[Option[OAuthClientRecord]]

  def createClient(client: OAuthClientRecord): IO[ClientAlreadyExists | Throwable, Unit]

  def updateClient(clientId: ClientId, patch: OAuthClientPatch): Task[Unit]

  def rotateClientSecret(clientId: ClientId, newSecret: Array[Byte]): Task[Unit]

  def deletePreviousClientSecret(clientId: ClientId): Task[Unit]

  def deleteClient(clientId: ClientId): Task[Unit]
