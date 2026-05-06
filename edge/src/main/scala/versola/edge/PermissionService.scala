package versola.edge

import versola.edge.model.{ClientId, OAuthClient, PermissionId, ResourceEndpointId, RoleId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZLayer}

trait PermissionService:
  def getAllowedEndpointsForRoles(roles: List[RoleId]): UIO[Set[ResourceEndpointId]]

  def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]]

object PermissionService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[RolesSyncClient & PermissionsSyncClient & OAuthClientsSyncClient & Scope, Throwable, PermissionService] =
    (
      ZLayer(ReloadingCache.make[Map[RoleId, Set[PermissionId]]](schedule)) ++        // roleId → permIds
      ZLayer(ReloadingCache.make[Map[PermissionId, Set[ResourceEndpointId]]](schedule)) ++ // permId → endpointIds
      ZLayer(ReloadingCache.make[Map[ClientId, OAuthClient]](schedule))           // clientId → OAuthClient
    ) >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      rolesCache: ReloadingCache[Map[RoleId, Set[PermissionId]]],
      permissionsCache: ReloadingCache[Map[PermissionId, Set[ResourceEndpointId]]],
      clientsCache: ReloadingCache[Map[ClientId, OAuthClient]],
  ) extends PermissionService:

    override def getAllowedEndpointsForRoles(roles: List[RoleId]): UIO[Set[ResourceEndpointId]] =
      for
        roleMap <- rolesCache.get
        permMap <- permissionsCache.get
        permIds = roles.flatMap(roleMap.getOrElse(_, Set.empty)).toSet
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))

    override def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]] =
      for
        clients <- clientsCache.get
        permMap <- permissionsCache.get
        permIds = clients.get(clientId).fold(Set.empty[PermissionId])(_.permissions)
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))
