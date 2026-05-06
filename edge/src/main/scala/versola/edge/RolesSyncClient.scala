package versola.edge

import versola.edge.model.{PermissionId, RoleId}
import versola.util.CacheSource
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Syncs role → permission ID mappings from central */
trait RolesSyncClient extends CacheSource[Map[RoleId, Set[PermissionId]]]:
  def getAll: Task[Map[RoleId, Set[PermissionId]]] // roleId → permissionIds

object RolesSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, RolesSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends RolesSyncClient:
    private val RolesURL = config.central.url / "configuration" / "roles" / "sync"

    override def getAll: Task[Map[RoleId, Set[PermissionId]]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(RolesURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        body <- response.bodyAs[GetRolesSyncResponse]
      yield body.roles
        .filter(_.active)
        .map(r => RoleId(r.id) -> r.permissions.map(PermissionId(_)))
        .toMap

    private case class RoleSyncRecord(
        id: String,
        permissions: Set[String],
        active: Boolean,
    ) derives JsonCodec

    private case class GetRolesSyncResponse(
        roles: Vector[RoleSyncRecord],
    ) derives JsonCodec
