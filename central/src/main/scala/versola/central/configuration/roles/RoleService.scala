package versola.central.configuration.roles

import versola.central.configuration.permissions.Permission
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateRoleRequest, RoleResponse, UpdateRoleRequest}
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, ZLayer, durationInt}

trait RoleService:
  def getTenantRoles(
      tenantId: TenantId,
      offset: Int = 0,
      limit: Option[Int] = None,
  ): Task[Vector[RoleRecord]]

  def createRole(
      request: CreateRoleRequest,
  ): Task[Unit]

  def updateRole(
      request: UpdateRoleRequest,
  ): Task[Unit]

  def markRoleInactive(tenantId: TenantId, id: RoleId): Task[Unit]

  def deleteRole(tenantId: TenantId, id: RoleId): Task[Unit]

  def sync(event: SyncEvent.RolesUpdated): Task[Unit]

object RoleService:
  def live(
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[RoleRepository & Scope, Throwable, RoleService] =
    ZLayer(ReloadingCache.make[Vector[RoleRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[RoleRecord]],
      roleRepository: RoleRepository,
  ) extends RoleService:

    export roleRepository.{deleteRole, markRoleInactive}

    override def getTenantRoles(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[RoleRecord]] =
      cache.get.map { records =>
        records
          .filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def createRole(
        request: CreateRoleRequest,
    ): Task[Unit] =
      roleRepository.createRole(
        tenantId = request.tenantId,
        id = request.id,
        description = request.description,
        permissions = request.permissions.toList,
      )

    override def updateRole(
        request: UpdateRoleRequest,
    ): Task[Unit] =
      roleRepository.updateRole(
        tenantId = request.tenantId,
        id = request.id,
        description = request.description,
        permissions = request.permissions,
      )

    override def sync(
        event: SyncEvent.RolesUpdated,
    ): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        roleRepository.findRole(event.tenantId, event.id),
      )
