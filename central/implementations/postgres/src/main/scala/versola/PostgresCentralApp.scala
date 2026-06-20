package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.HikariDataSource
import versola.central.CentralConfig
import versola.central.configuration.challenges.{ChallengeSettingsRepository, ChallengeSettingsService, OtpChallengeController, OtpChallengeRepository, OtpChallengeService}
import versola.central.configuration.clients.{AuthorizationPresetController, AuthorizationPresetRepository, AuthorizationPresetService, ClientController, OAuthClientRepository, OAuthClientService}
import versola.central.configuration.edges.{EdgeController, EdgeRepository, EdgeService}
import versola.central.configuration.forms.{FormController, FormRepository, FormService}
import versola.central.configuration.locales.{LocaleController, LocaleRepository, LocaleService}
import versola.central.configuration.themes.{ThemeController, ThemeRepository, ThemeService}
import versola.central.configuration.permissions.{PermissionController, PermissionRepository, PermissionService}
import versola.central.configuration.resources.{ResourceController, ResourceRepository, ResourceService}
import versola.central.configuration.roles.{RoleController, RoleRecord, RoleRepository, RoleService}
import versola.central.configuration.scopes.{OAuthScopeRepository, OAuthScopeService, ScopeController}
import versola.central.configuration.sync.{CacheSyncRepository, CacheSyncService}
import versola.central.configuration.tenants.{TenantController, TenantRepository, TenantService}
import versola.central.users.{AuthClient, UserOutboxProcessor, UserController, UserRepository, UserService}
import versola.configuration.clients.{PostgresAuthorizationPresetRepository, PostgresOAuthClientRepository}
import versola.configuration.challenges.{PostgresChallengeSettingsRepository, PostgresOtpChallengeRepository}
import versola.configuration.forms.PostgresFormRepository
import versola.configuration.locales.PostgresLocaleRepository
import versola.configuration.themes.PostgresThemeRepository
import versola.users.PostgresUserRepository
import versola.configuration.edges.PostgresEdgeRepository
import versola.configuration.permissions.PostgresPermissionRepository
import versola.configuration.resources.PostgresResourceRepository
import versola.configuration.roles.PostgresRoleRepository
import versola.configuration.scopes.PostgresOAuthScopeRepository
import versola.configuration.sync.PostgresCacheSyncRepository
import versola.configuration.tenants.PostgresTenantRepository
import versola.util.*
import versola.util.cel.CelEvaluator
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.config.magnolia.DeriveConfig
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object PostgresCentralApp extends VersolaApp("central"):
  override given Tag[Dependencies] = Tag[Dependencies]
  val environmentTag = Tag[Environment]

  type Dependencies =
    CentralConfig &
      TenantRepository &
      TenantService &
      PermissionRepository &
      PermissionService &
      ResourceRepository &
      ResourceService &
      OAuthClientRepository &
      OAuthClientService &
      AuthorizationPresetRepository &
      AuthorizationPresetService &
      OAuthScopeRepository &
      OAuthScopeService &
      RoleRepository &
      RoleService &
      EdgeRepository &
      EdgeService &
      FormRepository &
      FormService &
      LocaleRepository &
      LocaleService &
      ThemeRepository &
      ThemeService &
      OtpChallengeRepository &
      OtpChallengeService &
      ChallengeSettingsRepository &
      ChallengeSettingsService &
      CacheSyncRepository &
      CacheSyncService &
      UserRepository &
      UserService &
      SecureRandom &
      SecurityService

  override def routes: Routes[Dependencies & Tracing, Throwable] =
    List(
      TenantController.routes,
      PermissionController.routes,
      ResourceController.routes,
      ClientController.routes,
      AuthorizationPresetController.routes,
      ScopeController.routes,
      RoleController.routes,
      EdgeController.routes,
      FormController.routes,
      LocaleController.routes,
      ThemeController.routes,
      OtpChallengeController.routes,
      UserController.routes,
    ).reduce(_ ++ _)

  private val repositories =
    PostgresHikariDataSource.transactor(serviceName = Some("central"), migrate = true) >+>
      SecureRandom.live >+> (
        PostgresTenantRepository.live >+>
          PostgresPermissionRepository.live >+>
          PostgresResourceRepository.live >+>
          PostgresOAuthClientRepository.live >+>
          PostgresAuthorizationPresetRepository.live >+>
          PostgresOAuthScopeRepository.live >+>
          PostgresRoleRepository.live >+>
          PostgresEdgeRepository.live >+>
          PostgresFormRepository.live >+>
          PostgresLocaleRepository.live >+>
          PostgresThemeRepository.live >+>
          PostgresOtpChallengeRepository.live >+>
          PostgresChallengeSettingsRepository.live >+>
          PostgresCacheSyncRepository.live >+>
          PostgresUserRepository.live
      )

  override val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] = {
    val schedule = Schedule.spaced(1.minute)
    parseConfig[CentralConfig] >+>
      repositories >+>
      SecurityService.live >+>
      CelEvaluator.live >+>
      TenantService.live(schedule) >+>
      PermissionService.live(schedule) >+>
      ResourceService.live(schedule) >+>
      OAuthClientService.live(schedule) >+>
      AuthorizationPresetService.live(schedule) >+>
      OAuthScopeService.live(schedule) >+>
      RoleService.live(schedule) >+>
      EdgeService.live(schedule) >+>
      LocaleService.live >+>
      FormService.live(schedule) >+>
      ThemeService.live(schedule) >+>
      OtpChallengeService.live(schedule) >+>
      ChallengeSettingsService.live(schedule) >+>
      CacheSyncService.live >+>
      AuthClient.live >+>
      UserService.live >+>
      UserOutboxProcessor.live >+>
      MockDataService.live
  }

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail: str =>
      Secret.fromBase64Url(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[SecretKey] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))
    .map(bytes => SecretKeySpec(bytes, "AES"))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )
