package versola.central.configuration.roles

import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{CreateRoleRequest, RoleResponse, UpdateRoleRequest}
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, ZLayer, durationInt}

trait RoleService:
  def getTenantRoles(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[RoleRecord]]

  def getPermissionsForRoles(tenantId: TenantId, roleIds: Set[RoleId]): Task[Set[Permission]]

  def getRolesForSync(edgeId: Option[EdgeId]): Task[Vector[RoleRecord]]

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
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[RoleRepository & TenantRepository & Scope, Throwable, RoleService] =
    ZLayer(ReloadingCache.make[Vector[RoleRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[RoleRecord]],
      roleRepository: RoleRepository,
      tenantRepository: TenantRepository,
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

    override def getPermissionsForRoles(tenantId: TenantId, roleIds: Set[RoleId]): Task[Set[Permission]] =
      cache.get.map(
        _.filter(r => r.tenantId == tenantId && roleIds.contains(r.id))
          .flatMap(_.permissions)
          .toSet
      )

    override def getRolesForSync(edgeId: Option[EdgeId]): Task[Vector[RoleRecord]] =
      edgeId match
        case None => cache.get
        case Some(id) =>
          for
            roles <- cache.get
            tenants <- tenantRepository.getAll
            allowedTenantIds = tenants.filter(_.edgeId.contains(id)).map(_.id).toSet
          yield roles.filter(r => allowedTenantIds.contains(r.tenantId))

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
