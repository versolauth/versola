package versola.central.configuration.permissions

import versola.central.configuration.{CreatePermissionRequest, UpdatePermissionRequest}
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.stream.ZStream
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

trait PermissionService:
  def getTenantPermissions(
      tenantId: TenantId,
      offset: Int = 0,
      limit: Option[Int] = None,
  ): Task[Vector[PermissionRecord]]

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
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[PermissionRepository & Scope, Throwable, PermissionService] =
    ZLayer(ReloadingCache.make[Vector[PermissionRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[PermissionRecord]],
      permissionRepository: PermissionRepository,
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
