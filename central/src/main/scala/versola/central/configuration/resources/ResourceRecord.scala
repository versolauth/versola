package versola.central.configuration.resources

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.clients.ClientId
import versola.central.configuration.{InjectRule, ResourceUri}
import versola.util.Secret

case class ResourceRecord(
    tenantId: TenantId,
    resourceId: ResourceId,
    resource: ResourceUri,
    audience: List[ClientId],
    endpoints: Vector[ResourceEndpointRecord],
    secret: Option[Secret],
    previousSecret: Option[Secret],
):
  /** A resource with a secret is internal (edge authenticates to it with the
    * secret); one without is public (edge forwards the caller's own token). */
  def isInternal: Boolean = secret.nonEmpty

case class ResourceEndpointRecord(
    id: ResourceEndpointId,
    path: String,
    method: String,
    fetchUserInfo: Boolean,
    allowExpression: Option[String],
    inject: Vector[InjectRule],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAge: Option[Int],
) derives CanEqual
