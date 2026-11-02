package versola.central.configuration.resources

import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{InjectRule, ResourceUri}
import versola.util.Secret
import versola.util.http.DeclaredRateLimit
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** What the owner of a resource lets one client of its audience take: the limit applies to that client's calls to this resource,
  * checked at the edge together with the edge's own policies (the smaller wins). The client's own owner sets nothing.
  */
case class AudienceRateLimit(clientId: ClientId, rateLimit: DeclaredRateLimit) derives Schema, JsonCodec

case class ResourceRecord(
    tenantId: TenantId,
    resourceId: ResourceId,
    resource: ResourceUri,
    audience: List[ClientId],
    endpoints: Vector[ResourceEndpointRecord],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    /** The limits the resource sets for clients of its audience, at most one per client and only for clients that are in it. Last
      * and defaulted: a resource stored before the column has none. */
    audienceRateLimits: List[AudienceRateLimit] = Nil,
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
