package versola.oauth.client

import versola.oauth.client.model.{ClientId, ResourceId, ResourceRecord, ResourceUri, TenantId}
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Fetches the lightweight resource registry (id, tenant, URI) from central, used to
  * validate the RFC 8707 `resource` request parameter. Unlike [[OAuthClientSyncClient]],
  * resources carry no secret material relevant to auth (their secrets are only used
  * by edge to proxy internal resources), so no decryption is needed here.
  */
trait ResourceSyncClient extends CacheSource[Vector[ResourceRecord]]:
  def getAll: Task[Vector[ResourceRecord]]

object ResourceSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, ResourceSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ResourceSyncClient:
    private val RegistryURL = config.central.url / "configuration" / "resources" / "registry"

    override def getAll: Task[Vector[ResourceRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(RegistryURL)).flatMap(_.bodyAs[RegistryResponse])
      .map(_.resources.map(r => ResourceRecord(r.resourceId, r.tenantId, r.resource, r.audience, r.internal)))

    private case class RegistryEntry(
        resourceId: ResourceId,
        tenantId: TenantId,
        resource: ResourceUri,
        audience: List[ClientId],
        internal: Boolean,
    ) derives JsonCodec

    private case class RegistryResponse(resources: Vector[RegistryEntry]) derives JsonCodec
