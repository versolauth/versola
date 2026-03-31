package versola.central.configuration.sync

import versola.central.configuration.clients.{AuthorizationPresetService, OAuthClientService}
import versola.central.configuration.permissions.PermissionService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.RoleService
import versola.central.configuration.scopes.OAuthScopeService
import versola.central.configuration.tenants.TenantService
import zio.stream.ZSink
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CacheSyncService:
  def sync(): Task[Unit]

object CacheSyncService:
  def live: ZLayer[CacheSyncRepository & TenantService & PermissionService & ResourceService & OAuthClientService & OAuthScopeService & RoleService & AuthorizationPresetService & Scope, Nothing, CacheSyncService] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _)) >>>
      ZLayer(ZIO.serviceWithZIO[CacheSyncService.Impl](service => service.sync().forkScoped.as(service)))

  class Impl(
      repository: CacheSyncRepository,
      tenantService: TenantService,
      permissionService: PermissionService,
      resourceService: ResourceService,
      clientService: OAuthClientService,
      scopeService: OAuthScopeService,
      roleService: RoleService,
      presetService: AuthorizationPresetService,
  ) extends CacheSyncService:

    override def sync(): Task[Unit] =
      repository.getNotifications
        .runForeach {
          case SyncEvent.TenantsUpdated =>
            tenantService.sync().either

          case event: SyncEvent.RolesUpdated =>
            roleService.sync(event).either

          case event: SyncEvent.ClientsUpdated =>
            clientService.sync(event).either

          case event: SyncEvent.ScopesUpdated =>
            scopeService.sync(event).either

          case event: SyncEvent.PermissionsUpdated =>
            permissionService.sync(event).either

          case event: SyncEvent.ResourcesUpdated =>
            resourceService.sync(event).either

          case event: SyncEvent.PresetsUpdated =>
            presetService.sync(event).either

          case SyncEvent.Unknown =>
            ZIO.unit
        }
        .retry(Schedule.spaced(100.millis))
