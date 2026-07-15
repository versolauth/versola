package versola.central.configuration.sync

import versola.central.configuration.challenges.{ChallengeSettingsService, OtpChallengeService}
import versola.central.configuration.clients.{AuthorizationPresetService, OAuthClientService}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.forms.FormService
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.permissions.PermissionService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.RoleService
import versola.central.configuration.scopes.OAuthScopeService
import versola.central.configuration.system.SystemSettingsService
import versola.central.configuration.tenants.TenantService
import versola.central.configuration.themes.ThemeService
import versola.util.UnitSpecBase
import zio.*
import zio.stream.ZStream
import zio.test.*

object CacheSyncServiceSpec extends UnitSpecBase:

  class Env(events: SyncEvent*):
    val repository: CacheSyncRepository = new CacheSyncRepository:
      def getNotifications = ZStream.fromIterable(events)
    val tenantService          = stub[TenantService]
    val permissionService      = stub[PermissionService]
    val resourceService        = stub[ResourceService]
    val clientService          = stub[OAuthClientService]
    val scopeService           = stub[OAuthScopeService]
    val roleService            = stub[RoleService]
    val presetService          = stub[AuthorizationPresetService]
    val edgeService            = stub[EdgeService]
    val formService            = stub[FormService]
    val otpChallengeService    = stub[OtpChallengeService]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val systemSettingsService  = stub[SystemSettingsService]
    val jwksService            = stub[JwksService]
    val themeService           = stub[ThemeService]
    val service = CacheSyncService.Impl(
      repository, tenantService, permissionService, resourceService,
      clientService, scopeService, roleService, presetService, edgeService,
      formService, otpChallengeService, challengeSettingsService,
      systemSettingsService, jwksService, themeService,
    )

  def spec = suite("CacheSyncService")(
    test("routes ClientsUpdated DELETE to OAuthClientService.sync") {
      val event = SyncEvent.ClientsUpdated(versola.central.configuration.clients.ClientId("c1"), SyncEvent.Op.DELETE)
      val env = Env(event)
      for
        _ <- env.clientService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.clientService.sync.calls.nonEmpty)
    },
    test("routes ScopesUpdated UPDATE to OAuthScopeService.sync") {
      val event = SyncEvent.ScopesUpdated(
        versola.central.configuration.tenants.TenantId("t1"),
        versola.central.configuration.scopes.ScopeToken("read"),
        SyncEvent.Op.UPDATE,
      )
      val env = Env(event)
      for
        _ <- env.scopeService.sync.succeedsWith(())
        _ <- env.service.sync()
      yield assertTrue(env.scopeService.sync.calls.nonEmpty)
    },
  )
