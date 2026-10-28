package versola.edge.model

import versola.util.http.DeclaredRateLimit
import versola.util.{Base64, Secret}
import zio.http.URL
import zio.json.JsonCodec

case class ResourceEndpoint(
    id: ResourceEndpointId,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAge: Option[Int],
    /** A limit on how often this endpoint is called, applied at R3 together with the edge's own policies. */
    rateLimit: Option[DeclaredRateLimit] = None,
) derives JsonCodec:
  /** The registered route, never the concrete path: what R3 keys its buckets on and what a limit declared here is found by. */
  def template(resourceId: ResourceId): String = s"$method /resources/$resourceId$path"

/** What the owner of a resource lets one client of its audience take from it, checked at R3 with the edge's own policies. */
case class AudienceRateLimit(clientId: ClientId, rateLimit: DeclaredRateLimit) derives JsonCodec

/** `secret` is the decrypted secret edge authenticates to this resource with
  * (see [[ResourcesSyncClient]]). A resource with a secret is internal: edge sends
  * `Authorization: Basic <resourceId>:<secret>` upstream instead of forwarding the
  * caller's own token.
  */
case class Resource(
    resourceId: ResourceId,
    resource: URL,
    endpoints: Vector[ResourceEndpoint],
    secret: Option[Secret],
    audienceRateLimits: List[AudienceRateLimit] = Nil,
) derives JsonCodec

object Resource:
  given JsonCodec[URL] =
    JsonCodec.string.transformOrFail(URL.decode(_).left.map(_.getMessage), _.encode)

  given JsonCodec[Secret] =
    JsonCodec.string.transform(s => Secret(Base64.urlDecode(s)), Base64.urlEncode)
