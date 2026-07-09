package versola.edge.model

import zio.http.URL
import zio.json.JsonCodec

case class ResourceEndpoint(
    id: ResourceEndpointId,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
) derives JsonCodec

case class Resource(
    resourceId: ResourceId,
    resource: URL,
    endpoints: Vector[ResourceEndpoint],
) derives JsonCodec

object Resource:
  given JsonCodec[URL] =
    JsonCodec.string.transformOrFail(URL.decode(_).left.map(_.getMessage), _.encode)
