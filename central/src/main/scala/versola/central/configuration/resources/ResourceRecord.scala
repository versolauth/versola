package versola.central.configuration.resources

import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{InjectRule, ResourceUri}
import versola.util.Secret
import versola.util.http.DeclaredRateLimit

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
    /** A limit on how often this endpoint is called, checked at the edge together with the edge's own policies (the
      * smaller wins). `None` limits nothing; stored in the endpoint's JSON, so an endpoint written before it reads as `None`. */
    rateLimit: Option[DeclaredRateLimit] = None,
) derives CanEqual
