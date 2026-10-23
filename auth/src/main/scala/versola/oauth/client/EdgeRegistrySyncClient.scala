package versola.oauth.client

import versola.oauth.client.model.TenantId
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Fetches from central which edges exist and the tenants each is assigned to serve, so auth can
  * tell whether a certificate that names an edge belongs to one central still knows, and for
  * which tenants it may vouch (see `versola.oauth.mtls.EdgeCertificateTrust`).
  *
  * Central holds the edges registry -- deleting an edge there is how an operator cuts it off --
  * and auth is not on that database. This mirrors [[ResourceSyncClient]]: same endpoint shape,
  * same sync credentials, refreshed on the same configuration interval as every other registry
  * auth keeps a copy of. Ids and tenants only: auth recognises an edge by its certificate, not
  * by a key it holds.
  */
trait EdgeRegistrySyncClient extends CacheSource[Map[String, Set[TenantId]]]:
  def getAll: Task[Map[String, Set[TenantId]]]

object EdgeRegistrySyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, EdgeRegistrySyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends EdgeRegistrySyncClient:
    private val RegistryURL = config.central.url / "configuration" / "edges" / "registry"

    override def getAll: Task[Map[String, Set[TenantId]]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(RegistryURL)).flatMap(_.bodyAs[RegistryResponse])
      .map(_.edges.map(entry => entry.id -> entry.tenantIds.map(TenantId(_)).toSet).toMap)

    private case class RegistryEntry(
        id: String,
        tenantIds: List[String] = List.empty,
    ) derives JsonCodec

    private case class RegistryResponse(
        edges: List[RegistryEntry],
    ) derives JsonCodec
