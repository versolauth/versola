package versola.edge

import versola.edge.model.{ClientId, OAuthClient, PermissionId, ResourceEndpointId, RoleId, TenantId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait PermissionService:
  def getAllowedEndpointsForRoles(tenantId: TenantId, roles: List[RoleId]): UIO[Set[ResourceEndpointId]]

  def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]]

  def getPermissionsForRoles(
      tenantId: TenantId,
      roles: List[RoleId],
      endpointIds: Set[ResourceEndpointId],
  ): UIO[Set[PermissionId]]

  /** Reloads all three caches from central now, instead of waiting for
    * `configurationCacheRefreshInterval`. Backs the non-prod `/service/configuration/sync`
    * endpoint; nothing in request handling calls this. */
  def refreshNow: Task[Unit]

object PermissionService:
  def live: ZLayer[RolesSyncClient & PermissionsSyncClient & OAuthClientsSyncClient & Scope & EdgeConfig, Throwable, PermissionService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[(TenantId, RoleId), Set[PermissionId]]](config.configurationCacheRefreshInterval),
        )
      ) ++ // (tenantId, roleId) → permIds
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[PermissionId, Set[ResourceEndpointId]]](config.configurationCacheRefreshInterval),
        )
      ) ++ // permId → endpointIds
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[ClientId, OAuthClient]](config.configurationCacheRefreshInterval),
        )
      ) ++          // clientId → OAuthClient
      ZLayer.service[RolesSyncClient] ++
      ZLayer.service[PermissionsSyncClient] ++
      ZLayer.service[OAuthClientsSyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      rolesCache: ReloadingCache[Map[(TenantId, RoleId), Set[PermissionId]]],
      permissionsCache: ReloadingCache[Map[PermissionId, Set[ResourceEndpointId]]],
      clientsCache: ReloadingCache[Map[ClientId, OAuthClient]],
      rolesSource: RolesSyncClient,
      permissionsSource: PermissionsSyncClient,
      clientsSource: OAuthClientsSyncClient,
  ) extends PermissionService:

    private def permissionsFor(
        tenantId: TenantId,
        roles: List[RoleId],
        roleMap: Map[(TenantId, RoleId), Set[PermissionId]],
    ): Set[PermissionId] =
      roles.iterator.flatMap(roleId => roleMap.getOrElse((tenantId, roleId), Set.empty)).toSet

    override def getAllowedEndpointsForRoles(tenantId: TenantId, roles: List[RoleId]): UIO[Set[ResourceEndpointId]] =
      for
        roleMap <- rolesCache.get
        permMap <- permissionsCache.get
        permIds = permissionsFor(tenantId, roles, roleMap)
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))

    override def getAllowedEndpointsForClient(clientId: ClientId): UIO[Set[ResourceEndpointId]] =
      for
        clients <- clientsCache.get
        permMap <- permissionsCache.get
        permIds = clients.get(clientId).fold(Set.empty[PermissionId])(_.permissions)
      yield permIds.flatMap(permMap.getOrElse(_, Set.empty))

    override def getPermissionsForRoles(
        tenantId: TenantId,
        roles: List[RoleId],
        endpointIds: Set[ResourceEndpointId],
    ): UIO[Set[PermissionId]] =
      for
        roleMap <- rolesCache.get
        permMap <- permissionsCache.get
        permIds = permissionsFor(tenantId, roles, roleMap)
      yield permIds.filter(permId => permMap.getOrElse(permId, Set.empty).exists(endpointIds.contains))

    override def refreshNow: Task[Unit] =
      (
        rolesSource.getAll.flatMap(rolesCache.set) <&>
          permissionsSource.getAll.flatMap(permissionsCache.set) <&>
          clientsSource.getAll.flatMap(clientsCache.set)
      ).unit
