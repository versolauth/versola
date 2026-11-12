package versola.central.configuration.sync

import versola.central.configuration.challenges.{ChallengeSettingsService, OtpChallengeService}
import versola.central.configuration.clients.{AuthorizationPresetService, OAuthClientService}
import versola.central.configuration.delivery.DeliverySettingsService
import versola.central.configuration.details.AuthorizationDetailTypeService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.forms.FormService
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.permissions.PermissionService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.RoleService
import versola.central.configuration.scopes.OAuthScopeService
import versola.central.configuration.system.SystemSettingsService
import versola.central.configuration.tenants.TenantService
import versola.central.configuration.themes.ThemeService
import zio.stream.ZSink
import zio.{Duration, Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CacheSyncService:
  def sync(): Task[Unit]

object CacheSyncService:
  /** Upper bound for reloading one cache after the notification feed resubscribed. */
  val ReloadTimeout: Duration = 30.seconds

  def live: ZLayer[
    CacheSyncRepository & TenantService & PermissionService & ResourceService & OAuthClientService & OAuthScopeService & AuthorizationDetailTypeService & RoleService & AuthorizationPresetService & EdgeService & FormService & OtpChallengeService & ChallengeSettingsService & SystemSettingsService & DeliverySettingsService & JwksService & ThemeService & ServerMetadataService & Scope,
    Nothing,
    CacheSyncService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)) >>>
      ZLayer(ZIO.serviceWithZIO[CacheSyncService.Impl](service => service.sync().forkScoped.as(service)))

  class Impl(
      repository: CacheSyncRepository,
      tenantService: TenantService,
      permissionService: PermissionService,
      resourceService: ResourceService,
      clientService: OAuthClientService,
      scopeService: OAuthScopeService,
      authorizationDetailTypeService: AuthorizationDetailTypeService,
      roleService: RoleService,
      presetService: AuthorizationPresetService,
      edgeService: EdgeService,
      formService: FormService,
      otpChallengeService: OtpChallengeService,
      challengeSettingsService: ChallengeSettingsService,
      systemSettingsService: SystemSettingsService,
      deliverySettingsService: DeliverySettingsService,
      jwksService: JwksService,
      themeService: ThemeService,
      serverMetadataService: ServerMetadataService,
  ) extends CacheSyncService:

    // Named so a failed reload can be told apart in the log.
    private def reloads: List[(String, Task[Unit])] = List(
      "tenants" -> tenantService.sync(),
      "edges" -> edgeService.sync(),
      "jwks" -> jwksService.sync(),
      "roles" -> roleService.reload,
      "clients" -> clientService.refreshNow,
      "scopes" -> scopeService.reload,
      "authorization detail types" -> authorizationDetailTypeService.reload,
      "permissions" -> permissionService.reload,
      "resources" -> resourceService.reload,
      "presets" -> presetService.reload,
      "forms" -> formService.reload,
      "otp templates" -> otpChallengeService.reload,
      "challenge settings" -> challengeSettingsService.refreshNow,
      "themes" -> themeService.sync(),
      "system settings" -> systemSettingsService.sync(),
      "delivery settings" -> deliverySettingsService.sync(),
      "server metadata" -> serverMetadataService.sync(),
    )

    override def sync(): Task[Unit] =
      repository.getNotifications
        .runForeach {
          case SyncEvent.TenantsUpdated =>
            tenantService.sync().either

          case SyncEvent.EdgesUpdated =>
            edgeService.sync().either

          case SyncEvent.JwksUpdated =>
            jwksService.sync().either

          case event: SyncEvent.RolesUpdated =>
            roleService.sync(event).either

          case event: SyncEvent.ClientsUpdated =>
            clientService.sync(event).either

          case event: SyncEvent.ScopesUpdated =>
            scopeService.sync(event).either

          case event: SyncEvent.AuthorizationDetailTypesUpdated =>
            authorizationDetailTypeService.sync(event).either

          case event: SyncEvent.PermissionsUpdated =>
            permissionService.sync(event).either

          case event: SyncEvent.ResourcesUpdated =>
            resourceService.sync(event).either

          case event: SyncEvent.PresetsUpdated =>
            presetService.sync(event).either

          case event: SyncEvent.FormsUpdated =>
            formService.sync(event).either

          case event: SyncEvent.OtpTemplatesUpdated =>
            otpChallengeService.sync(event).either

          case event: SyncEvent.ChallengeSettingsUpdated =>
            challengeSettingsService.sync(event).either

          case SyncEvent.ThemesUpdated =>
            themeService.sync().either

          case SyncEvent.SystemSettingsUpdated =>
            systemSettingsService.sync().either

          case SyncEvent.DeliverySettingsUpdated =>
            deliverySettingsService.sync().either

          case SyncEvent.ServerMetadataUpdated =>
            serverMetadataService.sync().either

          case SyncEvent.Reload =>
            // The caches are independent, so they reload in parallel, each bounded by `ReloadTimeout`:
            // a stuck read (dead pooled connection, database still coming up) must not hold up the
            // per-record events queued behind this one. One failing or timed-out cache must not keep
            // the others stale, nor stop the stream; it keeps its previous value until its own timer reload.
            ZIO.foreachParDiscard(reloads): (name, reload) =>
              reload
                .timeout(ReloadTimeout)
                .flatMap:
                  case Some(_) => ZIO.unit
                  case None =>
                    ZIO.logWarning(
                      s"Reloading the $name cache after the feed resubscribed timed out after $ReloadTimeout; keeping the previous value",
                    )
                .catchAllCause(cause =>
                  ZIO.logWarningCause(s"Reloading the $name cache after the feed resubscribed failed; keeping the previous value", cause),
                )

          case SyncEvent.Unknown =>
            ZIO.unit
        }
        .retry(Schedule.spaced(100.millis))
