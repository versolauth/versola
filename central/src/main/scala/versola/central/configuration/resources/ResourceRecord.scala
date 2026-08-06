package versola.central.configuration.resources

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{InjectRule, ResourceUri}
import versola.util.Secret

case class ResourceRecord(
    tenantId: TenantId,
    resourceId: ResourceId,
    resource: ResourceUri,
    endpoints: Vector[ResourceEndpointRecord],
    credential: Option[Secret] = None,
    previousCredential: Option[Secret] = None,
):
  /** A resource with a credential is internal (edge authenticates to it with the
    * credential); one without is public (edge forwards the caller's own token). */
  def isInternal: Boolean = credential.nonEmpty

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
