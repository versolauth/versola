package versola.central.configuration.clients

import versola.central.configuration.partners.PartnerClientLimitReached
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.*

trait OAuthClientRepository extends CacheSource[Vector[OAuthClientRecord]]:

  def getAll: Task[Vector[OAuthClientRecord]]

  def find(clientId: ClientId): Task[Option[OAuthClientRecord]]

  /** Stores the client. For a client of a partner (`partnerId`) the partner's row is locked and its clients counted in the same
    * transaction, so that the partner's `maxClients` holds however many are created at once: [[PartnerClientLimitReached]] when the
    * partner has them all (its console client is not counted, and not refused). */
  def createClient(client: OAuthClientRecord): IO[ClientAlreadyExists | PartnerClientLimitReached | Throwable, Unit]

  def updateClient(clientId: ClientId, patch: OAuthClientPatch): Task[Unit]

  def rotateClientSecret(clientId: ClientId, newSecret: Array[Byte]): Task[Unit]

  def deletePreviousClientSecret(clientId: ClientId): Task[Unit]

  /** Where a partner's client stands with the operator. `false` when there is no such client. */
  def setApproval(clientId: ClientId, approval: ClientApproval): Task[Boolean]

  def deleteClient(clientId: ClientId): Task[Unit]
