package versola.edge

import versola.edge.model.{PermissionId, ResourceEndpointId}
import versola.util.CacheSource
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

import java.util.UUID

/** Syncs permission → endpoint ID mappings from central */
trait PermissionsSyncClient extends CacheSource[Map[PermissionId, Set[ResourceEndpointId]]]:
  def getAll: Task[Map[PermissionId, Set[ResourceEndpointId]]] // permissionId → endpointIds

object PermissionsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, PermissionsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends PermissionsSyncClient:
    private val PermissionsURL = config.central.url / "configuration" / "permissions" / "sync"

    override def getAll: Task[Map[PermissionId, Set[ResourceEndpointId]]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(PermissionsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        body <- response.bodyAs[GetPermissionsSyncResponse]
      yield body.permissions
        .map(p => PermissionId(p.id) -> p.endpointIds.map(ResourceEndpointId(_)))
        .toMap

    private case class PermissionSyncRecord(
        id: String,
        endpointIds: Set[UUID],
    ) derives JsonCodec

    private case class GetPermissionsSyncResponse(
        permissions: Vector[PermissionSyncRecord],
    ) derives JsonCodec
