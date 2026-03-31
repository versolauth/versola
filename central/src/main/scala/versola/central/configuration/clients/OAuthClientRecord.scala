package versola.central.configuration.clients

import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{RedirectUri, Secret}
import zio.prelude.Equal
import zio.schema.*
import zio.{Duration, NonEmptyChunk}

case class OAuthClientRecord(
    id: ClientId,
    tenantId: TenantId,
    clientName: String,
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    externalAudience: List[ClientId],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    accessTokenTtl: Duration,
    permissions: Set[Permission],
) derives Schema, CanEqual, Equal:

  def audience: List[ClientId] = id :: externalAudience

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential

  def hasPermission(permission: Permission): Boolean = permissions.contains(permission)
