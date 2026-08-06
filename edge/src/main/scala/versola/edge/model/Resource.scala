package versola.edge.model

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
) derives JsonCodec

/** `credential` is the decrypted credential edge authenticates to this resource with
  * (see [[ResourcesSyncClient]]). A resource with a credential is internal: edge sends
  * `Authorization: Basic <resourceId>:<credential>` upstream instead of forwarding the
  * caller's own token.
  */
case class Resource(
    resourceId: ResourceId,
    resource: URL,
    endpoints: Vector[ResourceEndpoint],
    credential: Option[Secret] = None,
) derives JsonCodec

object Resource:
  given JsonCodec[URL] =
    JsonCodec.string.transformOrFail(URL.decode(_).left.map(_.getMessage), _.encode)

  given JsonCodec[Secret] =
    JsonCodec.string.transform(s => Secret(Base64.urlDecode(s)), Base64.urlEncode)
