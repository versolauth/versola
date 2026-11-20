package versola.central.configuration.sync

import versola.central.configuration.challenges.{ChallengeSettingsService, OtpChallengeService, OtpTemplateChannel, OtpTemplatePurpose}
import versola.central.configuration.clients.{AuthorizationPresetService, OAuthClientService, PresetId}
import versola.central.configuration.delivery.DeliverySettingsService
import versola.central.configuration.details.AuthorizationDetailTypeService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.forms.{FormId, FormService}
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.partners.{PartnerId, PartnerRegistry}
import versola.central.configuration.permissions.{Permission, PermissionService}
import versola.central.configuration.resources.{ResourceId, ResourceService}
import versola.central.configuration.roles.{RoleId, RoleService}
import versola.central.configuration.scopes.OAuthScopeService
import versola.central.configuration.system.SystemSettingsService
import versola.central.configuration.tenants.{TenantId, TenantService}
import versola.central.configuration.themes.ThemeService
import versola.util.UnitSpecBase
import zio.*
import zio.stream.ZStream
import zio.test.*

object CacheSyncServiceSpec extends UnitSpecBase:

  class Env(events: SyncEvent*):
    val repository: CacheSyncRepository = new CacheSyncRepository:
      def getNotifications = ZStream.fromIterable(events)
    val tenantService = stub[TenantService]
    val permissionService = stub[PermissionService]
    val resourceService = stub[ResourceService]
    val clientService = stub[OAuthClientService]
    val scopeService = stub[OAuthScopeService]
    val authorizationDetailTypeService = stub[AuthorizationDetailTypeService]
    val roleService = stub[RoleService]
    val presetService = stub[AuthorizationPresetService]
    val edgeService = stub[EdgeService]
    val formService = stub[FormService]
    val otpChallengeService = stub[OtpChallengeService]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val systemSettingsService = stub[SystemSettingsService]
    val deliverySettingsService = stub[DeliverySettingsService]
    val jwksService = stub[JwksService]
    val themeService = stub[ThemeService]
    val serverMetadataService = stub[ServerMetadataService]
    val partnerRegistry = stub[PartnerRegistry]
    val service = CacheSyncService.Impl(
      repository,
      tenantService,
      permissionService,
      resourceService,
      clientService,
      scopeService,
      authorizationDetailTypeService,
      roleService,
      presetService,
      edgeService,
      formService,
      otpChallengeService,
      challengeSettingsService,
      systemSettingsService,
      deliverySettingsService,
      jwksService,
      themeService,
      serverMetadataService,
      partnerRegistry,
    )

  private enum RoleReload:
    case Succeeds, Fails, Dies, Hangs

  private def stubAllReloads(env: Env, role: RoleReload = RoleReload.Succeeds) =
    for
      _ <- (() => env.tenantService.sync()).succeedsWith(())
      _ <- (() => env.edgeService.sync()).succeedsWith(())
      _ <- (() => env.jwksService.sync()).succeedsWith(())
      _ <- role match
        case RoleReload.Succeeds => env.roleService.reload.succeedsWith(())
        case RoleReload.Fails => env.roleService.reload.failsWith(RuntimeException("db down"))
        case RoleReload.Dies => env.roleService.reload.returnsZIO(_ => ZIO.die(RuntimeException("defect")))
        case RoleReload.Hangs => env.roleService.reload.returnsZIO(_ => ZIO.never)
      _ <- env.clientService.refreshNow.succeedsWith(())
      _ <- env.scopeService.reload.succeedsWith(())
      _ <- env.authorizationDetailTypeService.reload.succeedsWith(())
      _ <- env.permissionService.reload.succeedsWith(())
      _ <- env.resourceService.reload.succeedsWith(())
      _ <- env.presetService.reload.succeedsWith(())
      _ <- env.formService.reload.succeedsWith(())
      _ <- env.otpChallengeService.reload.succeedsWith(())
      _ <- env.challengeSettingsService.refreshNow.succeedsWith(())
      _ <- (() => env.themeService.sync()).succeedsWith(())
      _ <- (() => env.systemSettingsService.sync()).succeedsWith(())
      _ <- (() => env.deliverySettingsService.sync()).succeedsWith(())
      _ <- (() => env.serverMetadataService.sync()).succeedsWith(())
    yield ()

  def spec = suite("CacheSyncService")(
    test("Reload reloads every cache") {
      val env = Env(SyncEvent.Reload)
      for
        _ <- stubAllReloads(env)
        _ <- env.service.sync()
      yield assertTrue(
        (() => env.tenantService.sync()).calls.size == 1,
        (() => env.edgeService.sync()).calls.size == 1,
        (() => env.jwksService.sync()).calls.size == 1,
        env.roleService.reload.calls.size == 1,
        env.clientService.refreshNow.calls.size == 1,
        env.scopeService.reload.calls.size == 1,
        env.authorizationDetailTypeService.reload.calls.size == 1,
        env.permissionService.reload.calls.size == 1,
        env.resourceService.reload.calls.size == 1,
        env.presetService.reload.calls.size == 1,
        env.formService.reload.calls.size == 1,
        env.otpChallengeService.reload.calls.size == 1,
        env.challengeSettingsService.refreshNow.calls.size == 1,
        (() => env.themeService.sync()).calls.size == 1,
        (() => env.systemSettingsService.sync()).calls.size == 1,
        (() => env.deliverySettingsService.sync()).calls.size == 1,
        (() => env.serverMetadataService.sync()).calls.size == 1,
      )
    },
    test("a cache that fails to reload does not stop the others or the stream") {
      val env = Env(SyncEvent.Reload, SyncEvent.TenantsUpdated)
      for
        _ <- stubAllReloads(env, RoleReload.Fails)
        _ <- env.service.sync()
      yield assertTrue(
        env.roleService.reload.calls.size == 1,
        env.formService.reload.calls.size == 1,
        (() => env.serverMetadataService.sync()).calls.size == 1,
        // the TenantsUpdated that follows the Reload is still handled
        (() => env.tenantService.sync()).calls.size == 2,
      )
    },
    test("a cache that dies with a defect while reloading does not stop the others or the stream") {
      val env = Env(SyncEvent.Reload, SyncEvent.TenantsUpdated)
      for
        _ <- stubAllReloads(env, RoleReload.Dies)
        _ <- env.service.sync()
      yield assertTrue(
        env.roleService.reload.calls.size == 1,
        env.formService.reload.calls.size == 1,
        (() => env.serverMetadataService.sync()).calls.size == 1,
        (() => env.tenantService.sync()).calls.size == 2,
      )
    },
    test("a cache whose reload hangs is abandoned after the timeout; the others are reloaded and the stream goes on") {
      val env = Env(SyncEvent.Reload, SyncEvent.TenantsUpdated)
      for
        _ <- stubAllReloads(env, RoleReload.Hangs)
        fiber <- env.service.sync().fork
        // While the roles reload is still hanging (far from its timeout), the other caches must already
        // be reloaded: a sequential loop would still be stuck on roles and never reach forms.
        _ <- TestClock.adjust(1.second)
        reloadedMeanwhile = (
          env.formService.reload.calls.size == 1 &&
            (() => env.serverMetadataService.sync()).calls.size == 1
        )
        stillRunning <- fiber.poll.map(_.isEmpty)
        _ <- TestClock.adjust(CacheSyncService.ReloadTimeout)
        _ <- fiber.join
      yield assertTrue(
        reloadedMeanwhile,
        stillRunning,
        env.roleService.reload.calls.size == 1,
        // the TenantsUpdated that follows the Reload is handled once the stuck reload timed out
        (() => env.tenantService.sync()).calls.size == 2,
      )
    },
    test("routes ClientsUpdated DELETE to OAuthClientService.sync") {
      val event = SyncEvent.ClientsUpdated(versola.central.configuration.clients.ClientId("c1"), SyncEvent.Op.DELETE)
      val env = Env(event)
      for
        _ <- env.clientService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.clientService.sync.calls.nonEmpty)
    },
    test("routes PartnersUpdated to PartnerRegistry.sync") {
      val event = SyncEvent.PartnersUpdated(PartnerId("acme"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.partnerRegistry.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.partnerRegistry.sync.calls == List(event))
    },
    test("routes ScopesUpdated UPDATE to OAuthScopeService.sync") {
      val event = SyncEvent.ScopesUpdated(
        TenantId("t1"),
        versola.central.configuration.scopes.ScopeToken("read"),
        SyncEvent.Op.UPDATE,
      )
      val env = Env(event)
      for
        _ <- env.scopeService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.scopeService.sync.calls.nonEmpty)
    },
    test("routes ServerMetadataUpdated to ServerMetadataService.sync") {
      val env = Env(SyncEvent.ServerMetadataUpdated)
      for
        _ <- (() => env.serverMetadataService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.serverMetadataService.sync()).calls.nonEmpty)
    },
    test("routes TenantsUpdated to TenantService.sync") {
      val env = Env(SyncEvent.TenantsUpdated)
      for
        _ <- (() => env.tenantService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.tenantService.sync()).calls.nonEmpty)
    },
    test("routes EdgesUpdated to EdgeService.sync") {
      val env = Env(SyncEvent.EdgesUpdated)
      for
        _ <- (() => env.edgeService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.edgeService.sync()).calls.nonEmpty)
    },
    test("routes JwksUpdated to JwksService.sync") {
      val env = Env(SyncEvent.JwksUpdated)
      for
        _ <- (() => env.jwksService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.jwksService.sync()).calls.nonEmpty)
    },
    test("routes ThemesUpdated to ThemeService.sync") {
      val env = Env(SyncEvent.ThemesUpdated)
      for
        _ <- (() => env.themeService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.themeService.sync()).calls.nonEmpty)
    },
    test("routes SystemSettingsUpdated to SystemSettingsService.sync") {
      val env = Env(SyncEvent.SystemSettingsUpdated)
      for
        _ <- (() => env.systemSettingsService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.systemSettingsService.sync()).calls.nonEmpty)
    },
    test("routes DeliverySettingsUpdated to DeliverySettingsService.sync") {
      val env = Env(SyncEvent.DeliverySettingsUpdated)
      for
        _ <- (() => env.deliverySettingsService.sync()).succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue((() => env.deliverySettingsService.sync()).calls.nonEmpty)
    },
    test("routes RolesUpdated to RoleService.sync") {
      val event = SyncEvent.RolesUpdated(TenantId("t1"), RoleId("r1"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.roleService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.roleService.sync.calls.nonEmpty)
    },
    test("routes PermissionsUpdated to PermissionService.sync") {
      val event = SyncEvent.PermissionsUpdated(TenantId("t1"), Permission("read"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.permissionService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.permissionService.sync.calls.nonEmpty)
    },
    test("routes ResourcesUpdated to ResourceService.sync") {
      val event = SyncEvent.ResourcesUpdated(TenantId("t1"), ResourceId("r1"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.resourceService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.resourceService.sync.calls.nonEmpty)
    },
    test("routes PresetsUpdated to AuthorizationPresetService.sync") {
      val event = SyncEvent.PresetsUpdated(TenantId("t1"), PresetId("p1"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.presetService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.presetService.sync.calls.nonEmpty)
    },
    test("routes FormsUpdated to FormService.sync") {
      val event = SyncEvent.FormsUpdated(FormId("f1"), 1, SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.formService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.formService.sync.calls.nonEmpty)
    },
    test("routes OtpTemplatesUpdated to OtpChallengeService.sync") {
      val event = SyncEvent.OtpTemplatesUpdated(TenantId("t1"), "template-1", OtpTemplatePurpose.otp, OtpTemplateChannel.sms, SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.otpChallengeService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.otpChallengeService.sync.calls.nonEmpty)
    },
    test("routes ChallengeSettingsUpdated to ChallengeSettingsService.sync") {
      val event = SyncEvent.ChallengeSettingsUpdated(TenantId("t1"), SyncEvent.Op.UPDATE)
      val env = Env(event)
      for
        _ <- env.challengeSettingsService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.challengeSettingsService.sync.calls.nonEmpty)
    },
  )
