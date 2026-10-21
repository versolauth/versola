package versola.central.configuration.roles

import versola.central.CentralConfig
import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{CreateRoleRequest, RoleResponse, UpdateRoleRequest}
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

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
  /** The role is one of the tenant's `defaultRoles`: deleting it would make every new registration grant
    * a role that does not exist. */
  final class RoleInUseError(val roleId: RoleId)
    extends RuntimeException(s"Role '$roleId' is a default role for new registrations and cannot be deleted")

  def live: ZLayer[RoleRepository & TenantRepository & ChallengeSettingsService & Scope & CentralConfig, Throwable, RoleService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[RoleRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[RoleRecord]],
      roleRepository: RoleRepository,
      tenantRepository: TenantRepository,
      challengeSettingsService: ChallengeSettingsService,
  ) extends RoleService:

    export roleRepository.markRoleInactive

    override def deleteRole(tenantId: TenantId, id: RoleId): Task[Unit] =
      challengeSettingsService.getSettings(tenantId).flatMap: settings =>
        ZIO.fail(RoleInUseError(id)).when(settings.exists(_.defaultRoles.contains(id))) *>
          roleRepository.deleteRole(tenantId, id)

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
          .toSet,
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
        active = request.active,
      )

    override def updateRole(
        request: UpdateRoleRequest,
    ): Task[Unit] =
      roleRepository.updateRole(
        tenantId = request.tenantId,
        id = request.id,
        description = request.description,
        permissions = request.permissions,
        active = request.active,
      )

    override def sync(
        event: SyncEvent.RolesUpdated,
    ): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        roleRepository.findRole(event.tenantId, event.id),
      )
