package versola.edge

import versola.edge.model.{ClientId, OAuthClient, PermissionId, ResourceEndpointId, RoleId, TenantId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZIO, ZLayer}

trait PermissionService:
  def getAllowedEndpointsForRoles(roles: Map[TenantId, List[RoleId]]): UIO[Set[ResourceEndpointId]]

  def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]]

  def getPermissionsForRoles(
      roles: Map[TenantId, List[RoleId]],
      endpointIds: Set[ResourceEndpointId],
  ): UIO[Set[PermissionId]]

object PermissionService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[RolesSyncClient & PermissionsSyncClient & OAuthClientsSyncClient & Scope, Throwable, PermissionService] =
    (
      ZLayer(ReloadingCache.make[Map[(TenantId, RoleId), Set[PermissionId]]](schedule)) ++ // (tenantId, roleId) → permIds
      ZLayer(ReloadingCache.make[Map[PermissionId, Set[ResourceEndpointId]]](schedule)) ++ // permId → endpointIds
      ZLayer(ReloadingCache.make[Map[ClientId, OAuthClient]](schedule))           // clientId → OAuthClient
    ) >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      rolesCache: ReloadingCache[Map[(TenantId, RoleId), Set[PermissionId]]],
      permissionsCache: ReloadingCache[Map[PermissionId, Set[ResourceEndpointId]]],
      clientsCache: ReloadingCache[Map[ClientId, OAuthClient]],
  ) extends PermissionService:

    private def permissionsFor(
        roles: Map[TenantId, List[RoleId]],
        roleMap: Map[(TenantId, RoleId), Set[PermissionId]],
    ): Set[PermissionId] =
      roles.iterator.flatMap { (tenantId, roleIds) =>
        roleIds.flatMap(roleId => roleMap.getOrElse((tenantId, roleId), Set.empty))
      }.toSet

    override def getAllowedEndpointsForRoles(roles: Map[TenantId, List[RoleId]]): UIO[Set[ResourceEndpointId]] =
      for
        roleMap <- rolesCache.get
        permMap <- permissionsCache.get
        permIds = permissionsFor(roles, roleMap)
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))

    override def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]] =
      for
        clients <- clientsCache.get
        permMap <- permissionsCache.get
        permIds = clients.get(clientId).fold(Set.empty[PermissionId])(_.permissions)
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))

    override def getPermissionsForRoles(
        roles: Map[TenantId, List[RoleId]],
        endpointIds: Set[ResourceEndpointId],
    ): UIO[Set[PermissionId]] =
      for
        roleMap <- rolesCache.get
        permMap <- permissionsCache.get
        permIds = permissionsFor(roles, roleMap)
      yield permIds.filter(permId => permMap.getOrElse(permId, Set.empty).exists(endpointIds.contains))
