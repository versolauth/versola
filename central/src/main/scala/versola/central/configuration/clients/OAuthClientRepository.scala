package versola.central.configuration.clients

import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.CacheSource
import zio.*

trait OAuthClientRepository extends CacheSource[Vector[OAuthClientRecord]]:

  def getAll: Task[Vector[OAuthClientRecord]]

  def find(tenantId: TenantId, clientId: ClientId): Task[Option[OAuthClientRecord]]

  def createClient(client: OAuthClientRecord): Task[Unit]

  def updateClient(
      tenantId: TenantId,
      clientId: ClientId,
      clientName: Option[String],
      patchRedirectUris: PatchClientRedirectUris,
      patchScope: PatchClientScope,
      patchPermissions: PatchPermissions,
      accessTokenTtl: Option[Duration],
  ): Task[Unit]

  def rotateClientSecret(tenantId: TenantId, clientId: ClientId, newSecret: Array[Byte]): Task[Unit]

  def deletePreviousClientSecret(tenantId: TenantId, clientId: ClientId): Task[Unit]

  def deleteClient(tenantId: TenantId, clientId: ClientId): Task[Unit]
