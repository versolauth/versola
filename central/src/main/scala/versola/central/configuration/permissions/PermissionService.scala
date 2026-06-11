package versola.central.configuration.permissions

import versola.central.configuration.{CreatePermissionRequest, UpdatePermissionRequest}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.util.ReloadingCache
import zio.stream.ZStream
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

trait PermissionService:
  def getTenantPermissions(
      tenantId: TenantId,
      offset: Int = 0,
      limit: Option[Int] = None,
  ): Task[Vector[PermissionRecord]]

  def getPermissionsForSync(edgeId: Option[EdgeId]): Task[Vector[PermissionRecord]]

  def createPermission(
      request: CreatePermissionRequest,
  ): Task[Unit]

  def updatePermission(
      request: UpdatePermissionRequest,
  ): Task[Unit]

  def deletePermission(
      tenantId: Option[TenantId],
      permission: Permission,
  ): Task[Unit]

  def sync(
      event: SyncEvent.PermissionsUpdated,
  ): Task[Unit]

object PermissionService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[PermissionRepository & TenantRepository & Scope, Throwable, PermissionService] =
    ZLayer(ReloadingCache.make[Vector[PermissionRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[PermissionRecord]],
      permissionRepository: PermissionRepository,
      tenantRepository: TenantRepository,
  ) extends PermissionService:
    export permissionRepository.deletePermission

    override def getTenantPermissions(
        tenantId: TenantId,
        offset: Int = 0,
        limit: Option[Int] = None,
    ): Task[Vector[PermissionRecord]] =
      cache.get.map { records =>
        records
          .filter(record => record.tenantId.isEmpty || record.tenantId.contains(tenantId))
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def getPermissionsForSync(edgeId: Option[EdgeId]): Task[Vector[PermissionRecord]] =
      edgeId match
        case None => cache.get
        case Some(id) =>
          for
            permissions <- cache.get
            tenants <- tenantRepository.getAll
            allowedTenantIds = tenants.filter(_.edgeId.contains(id)).map(_.id).toSet
          yield permissions.filter(p => p.tenantId.isEmpty || p.tenantId.exists(allowedTenantIds.contains))

    override def createPermission(
        request: CreatePermissionRequest,
    ): Task[Unit] =
      permissionRepository.createPermission(
        tenantId = request.tenantId,
        permission = request.permission,
        description = request.description,
        endpointIds = request.endpointIds,
      )

    override def updatePermission(
        request: UpdatePermissionRequest,
    ): Task[Unit] =
      permissionRepository.updatePermission(
        tenantId = request.tenantId,
        permission = request.permission,
        descriptionPatch = request.description,
        endpointIds = request.endpointIds,
      )

    override def sync(
        event: SyncEvent.PermissionsUpdated,
    ): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        permissionRepository.findPermission(event.tenantId, event.id),
      )
