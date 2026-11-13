package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.HikariDataSource
import versola.audit.PostgresAuditEventRepository
import versola.central.antifraud.{
  AntifraudCountersController,
  AntifraudCountersService,
  AntifraudListController,
  AntifraudListService,
  AntifraudSettingsController,
  AntifraudSettingsService,
  AntifraudStatusController,
  AntifraudStatusService,
}
import versola.central.audit.{AuditHook, AuditReportService, CentralAudit, CentralAuditRepository}
import versola.central.configuration.challenges.{
  ChallengeSettingsRepository,
  ChallengeSettingsService,
  OtpChallengeController,
  OtpChallengeRepository,
  OtpChallengeService,
}
import versola.central.configuration.clients.certificates.{
  ClientCertificateIssuanceRepository,
  ClientCertificateIssuer,
  ClientCertificateService,
  EdgeCertificateEnrollmentRepository,
}
import versola.central.configuration.clients.attestation.{
  ClientAttestationController,
  ClientAttestationRepository,
  ClientAttestationService,
}
import versola.central.configuration.clients.est.{ClientEstController, ClientEstRepository, ClientEstService}
import versola.central.configuration.clients.{
  AuthorizationPresetController,
  AuthorizationPresetRepository,
  AuthorizationPresetService,
  ClientCertificateAuthority,
  ClientController,
  OAuthClientRepository,
  OAuthClientService,
}
import versola.central.configuration.delivery.{DeliverySettingsController, DeliverySettingsRepository, DeliverySettingsService}
import versola.central.configuration.details.{AuthorizationDetailTypeController, AuthorizationDetailTypeRepository, AuthorizationDetailTypeService}
import versola.central.configuration.edges.{
  EdgeCertificateIssuer,
  EdgeCertificateRepository,
  EdgeController,
  EdgeEnrollController,
  EdgeEnrollmentTokenRepository,
  EdgeGroupRepository,
  EdgeGroupService,
  EdgeIdentityChainRepository,
  EdgeIdentityService,
  EdgeRepository,
  EdgeService,
  EdgeSyncController,
}
import versola.central.configuration.forms.{FormController, FormRepository, FormService}
import versola.central.configuration.jwks.{JwksController, JwksService}
import versola.central.configuration.locales.{LocaleCompletenessValidator, LocaleController, LocaleRepository, LocaleService}
import versola.central.configuration.metadata.{ServerMetadataController, ServerMetadataRepository, ServerMetadataService}
import versola.central.configuration.permissions.{PermissionController, PermissionRepository, PermissionService}
import versola.central.configuration.resources.{ResourceController, ResourceRepository, ResourceService}
import versola.central.configuration.roles.{RoleController, RoleRecord, RoleRepository, RoleService}
import versola.central.configuration.scopes.{OAuthScopeRepository, OAuthScopeService, ScopeController}
import versola.central.configuration.sync.{CacheSyncRepository, CacheSyncService}
import versola.central.configuration.system.{SystemSettingsController, SystemSettingsRepository, SystemSettingsService}
import versola.central.configuration.tenants.{TenantController, TenantRepository, TenantService}
import versola.central.configuration.themes.{ThemeController, ThemeRepository, ThemeService}
import versola.central.users.{AuthClient, ServiceController, UserController, UserOutboxProcessor, UserRepository, UserService}
import versola.central.{CentralConfig, refuseEdgeCallers}
import versola.configuration.challenges.{PostgresChallengeSettingsRepository, PostgresOtpChallengeRepository}
import versola.configuration.clients.{
  PostgresAuthorizationPresetRepository,
  PostgresClientAttestationRepository,
  PostgresClientCertificateIssuanceRepository,
  PostgresClientEstRepository,
  PostgresEdgeCertificateEnrollmentRepository,
  PostgresOAuthClientRepository,
}
import versola.configuration.delivery.PostgresDeliverySettingsRepository
import versola.configuration.details.PostgresAuthorizationDetailTypeRepository
import versola.configuration.edges.{
  PostgresEdgeCertificateRepository,
  PostgresEdgeEnrollmentTokenRepository,
  PostgresEdgeGroupRepository,
  PostgresEdgeIdentityChainRepository,
  PostgresEdgeRepository,
}
import versola.configuration.forms.PostgresFormRepository
import versola.configuration.jwks.PostgresJwksRepository
import versola.configuration.locales.PostgresLocaleRepository
import versola.configuration.metadata.PostgresServerMetadataRepository
import versola.configuration.permissions.PostgresPermissionRepository
import versola.configuration.resources.PostgresResourceRepository
import versola.configuration.roles.PostgresRoleRepository
import versola.configuration.scopes.PostgresOAuthScopeRepository
import versola.configuration.sync.PostgresCacheSyncRepository
import versola.configuration.system.PostgresSystemSettingsRepository
import versola.configuration.tenants.PostgresTenantRepository
import versola.configuration.themes.PostgresThemeRepository
import versola.users.PostgresUserRepository
import versola.util.*
import versola.util.SequentialLayers.*
import versola.util.audit.{AuditEventRepository, AuditEvents}
import versola.util.cel.CelEvaluator
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource, PostgresWalMetrics}
import zio.*
import zio.config.magnolia.DeriveConfig
import zio.http.*
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.lang.System as JavaSystem
import java.net.InetAddress
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

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
      AuthorizationDetailTypeRepository &
      AuthorizationDetailTypeService &
      RoleRepository &
      RoleService &
      EdgeRepository &
      EdgeGroupRepository &
      EdgeEnrollmentTokenRepository &
      EdgeIdentityChainRepository &
      EdgeCertificateRepository &
      EdgeService &
      EdgeGroupService &
      EdgeCertificateIssuer &
      EdgeIdentityService &
      FormRepository &
      FormService &
      LocaleRepository &
      LocaleCompletenessValidator &
      LocaleService &
      ThemeRepository &
      ThemeService &
      OtpChallengeRepository &
      OtpChallengeService &
      ChallengeSettingsRepository &
      ChallengeSettingsService &
      SystemSettingsRepository &
      DeliverySettingsRepository &
      SystemSettingsService &
      DeliverySettingsService &
      CacheSyncRepository &
      CacheSyncService &
      SecurityService &
      UserRepository &
      UserService &
      AntifraudListService &
      AntifraudSettingsService &
      AntifraudCountersService &
      AntifraudStatusService &
      AuthClient &
      CentralAuditRepository &
      AuditEventRepository &
      AuditEvents.Settings &
      AuditEvents &
      AuditReportService &
      UserOutboxProcessor &
      ClientCertificateIssuanceRepository &
      EdgeCertificateEnrollmentRepository &
      ClientEstRepository &
      ClientAttestationRepository &
      ClientCertificateIssuer &
      ClientCertificateService &
      ClientEstService &
      ClientAttestationService &
      ServerMetadataRepository &
      ServerMetadataService &
      JwksService

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    // #554: an edge reaches central through `EdgeSyncController`, on the mutual-TLS listener,
    // and nothing else. Every other
    // controller is auth's or the admin console's and refuses an edge token here, as a group,
    // so a controller added to this list is auth-only without doing anything about it.
    // #469: what an administrator does is recorded on every one of these routes by one hook, which takes what each route means from
    // the route's own declaration. The service surface is declared internal: the operator's tooling is not recorded.
    Routes.fromIterable(adminEndpoints.map(AuditHook.wrap)) @@ refuseEdgeCallers

  /** Every route of the main listener, each with the declaration the audit log takes its meaning from: a controller lists only
    * [[AuditedRoute]]s, so there is none without one. */
  val adminEndpoints =
    List(
      TenantController.endpoints,
      PermissionController.endpoints,
      ResourceController.endpoints,
      ClientController.endpoints,
      ClientEstController.endpoints,
      ClientAttestationController.endpoints,
      AuthorizationPresetController.endpoints,
      ScopeController.endpoints,
      AuthorizationDetailTypeController.endpoints,
      RoleController.endpoints,
      EdgeController.endpoints,
      FormController.endpoints,
      LocaleController.endpoints,
      ThemeController.endpoints,
      OtpChallengeController.endpoints,
      SystemSettingsController.endpoints,
      DeliverySettingsController.endpoints,
      UserController.endpoints,
      AntifraudListController.endpoints,
      AntifraudSettingsController.endpoints,
      AntifraudCountersController.endpoints,
      AntifraudStatusController.endpoints,
      JwksController.endpoints,
      ServerMetadataController.endpoints,
      ServiceController.endpoints,
    ).flatten

  /** The one route an edge reaches before it has a certificate (`POST /edge/enroll`), on the additional listener
    * (`APORT`): plain HTTP meant to sit behind the TLS terminator of the public name for central, which makes it the
    * public one of central's three ports (`PORT` is internal, `MPORT` is the edges' mutual TLS). Nothing else is
    * served there.
    *
    * Only where `APORT` is set. Unlike auth, which has always served its additional listener on the default, central
    * has none unless asked: binding 8082 by default would take a port another service uses on a host where central
    * and auth share the network (vps), and an edge that is not remote has no use for enrollment at all. */
  override def additionalRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Option.when(JavaSystem.getenv("APORT") != null)(EdgeEnrollController.routes)

  /** The edge surface, on its own mutual-TLS listener and not on the main one: an edge is
    * authenticated by the certificate it presents in the handshake as well as by its token. */
  override def mutualTlsRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Some(EdgeSyncController.routes)

  /** `ClientAuth.Required` for the reasons given on `PostgresOAuthApp.mutualTlsServerConfig`:
    * `Optional` with `includeClientCert` drops the connection of a client that sends no
    * certificate, and no edge route is reachable without one anyway. */
  override def mutualTlsServerConfig: ZIO[Dependencies, Throwable, Option[Server.Config]] =
    ZIO.serviceWith[CentralConfig](
      _.mutualTls.map(mtls =>
        Server.Config.default.binding(mutualTlsBindHost, mutualTlsPort).ssl(
          SSLConfig.fromFile(
            behaviour = SSLConfig.HttpBehaviour.Fail,
            certPath = mtls.certificate,
            keyPath = mtls.privateKey,
            clientAuth = Some(ClientAuth.Required),
            trustCertCollectionPath = Some(mtls.trustedCertificates),
            includeClientCert = true,
          ),
        ),
      ),
    )

  private val repositories =
    PostgresHikariDataSource.transactor(serviceName = Some("central"), migrate = runMigrations) >++>
      PostgresWalMetrics.live() >++>
      SecureRandom.live >++> (
        PostgresTenantRepository.live >++>
          PostgresPermissionRepository.live >++>
          PostgresResourceRepository.live >++>
          PostgresOAuthClientRepository.live >++>
          PostgresClientCertificateIssuanceRepository.live >++>
          PostgresEdgeCertificateEnrollmentRepository.live >++>
          PostgresClientEstRepository.live >++>
          PostgresClientAttestationRepository.live >++>
          PostgresAuthorizationPresetRepository.live >++>
          PostgresOAuthScopeRepository.live >++>
          PostgresAuthorizationDetailTypeRepository.live >++>
          PostgresRoleRepository.live >++>
          PostgresEdgeRepository.live >++>
          PostgresEdgeGroupRepository.live >++>
          PostgresEdgeEnrollmentTokenRepository.live >++>
          PostgresEdgeIdentityChainRepository.live >++>
          PostgresEdgeCertificateRepository.live >++>
          PostgresFormRepository.live >++>
          PostgresLocaleRepository.live >++>
          PostgresThemeRepository.live >++>
          PostgresOtpChallengeRepository.live >++>
          PostgresChallengeSettingsRepository.live >++>
          PostgresSystemSettingsRepository.live >++>
          PostgresDeliverySettingsRepository.live >++>
          PostgresCacheSyncRepository.live >++>
          PostgresJwksRepository.live >++>
          PostgresServerMetadataRepository.live >++>
          PostgresAuditEventRepository.live >++>
          PostgresUserRepository.live
      )

  /** This process in the audit log: its host, which is what tells the instances of a deployment apart, and a part that is its own
    * (a host can run more than one). */
  private val nodeId: String =
    val host = Try(InetAddress.getLocalHost.getHostName).getOrElse("central")
    s"$host-${UUID.randomUUID().toString.take(8)}"

  /** The audit log's writer (#469): it makes the partitions of `central_audit_event` and writes the events the services record, and writes
    * what is queued once more when its scope closes. It is built on the transactor and the repository, before everything that records:
    * bootstrap does. */
  private[versola] def auditWriter(
      sizing: AuditEvents.Sizing = AuditEvents.Sizing.standard,
  ): ZLayer[AuditEventRepository & AuditEvents.Settings & Tracing & SecureRandom & Scope, Nothing, AuditEvents] =
    AuditEvents.liveWith(sizing)(CentralAudit.Source, nodeId, CentralAudit.catalog, CentralAudit.Table)

  /** The audit log: its settings from the `audit` block of the config, and the writer. */
  private val auditEvents
      : ZLayer[CentralConfig & AuditEventRepository & Tracing & SecureRandom & Scope, Nothing, AuditEvents.Settings & AuditEvents] =
    ZLayer.fromFunction((config: CentralConfig) => config.audit.settings) >++> auditWriter()

  override val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    parseConfig[CentralConfig] >++>
      repositories >++>
      auditEvents >++>
      SecurityService.live >++>
      CelEvaluator.live >++>
      JsonSchemaValidator.live >++>
      ChallengeSettingsService.signingKeyReferences >++>
      ChallengeSettingsService.live >++>
      // RFC 8705 §6.5: registering `mtlsAuth` is refused unless the client's tenant names a
      // certificate header, so client registration reads the challenge settings.
      ClientCertificateAuthority.live >++>
      OAuthClientService.live >++>
      ClientCertificateIssuer.live >++>
      EdgeCertificateIssuer.live >++>
      ClientCertificateService.live >++>
      ClientEstService.live >++>
      ClientAttestationService.live >++>
      BootstrapService.live >++>
      TenantService.live >++>
      PermissionService.live >++>
      ResourceService.live >++>
      AuthorizationPresetService.live >++>
      OAuthScopeService.live >++>
      ServerMetadataService.live >++>
      AuthorizationDetailTypeService.live >++>
      RoleService.live >++>
      EdgeService.live >++>
      EdgeGroupService.live >++>
      EdgeIdentityService.live >++>
      LocaleCompletenessValidator.live >++> LocaleService.live >++>
      JwksService.live >++>
      FormService.live >++>
      ThemeService.live >++>
      OtpChallengeService.live >++>
      SystemSettingsService.live >++>
      DeliverySettingsService.live >++>
      CacheSyncService.live >++>
      AuthClient.live >++>
      AuditReportService.live >++>
      UserService.live >++>
      AntifraudListService.live >++>
      AntifraudSettingsService.live >++>
      AntifraudCountersService.live >++>
      AntifraudStatusService.live >++>
      UserOutboxProcessor.live >++>
      // Renews the certificates central issued, for as long as it runs. A no-op where
      // `client-certificates` is not configured.
      ClientCertificateService.renewal

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail(SecretConfig.secret)

  given DeriveConfig[AuditEvents.Overflow] = DeriveConfig[String]
    .mapOrFail(name =>
      AuditEvents.Overflow.values.find(_.toString == name)
        .toRight(zio.Config.Error.InvalidData(message = s"audit.overflow is 'block' or 'drop', not '$name'")),
    )

  given DeriveConfig[CentralConfig.AuditConfig] =
    DeriveConfig.derived[CentralConfig.AuditConfig].mapOrFail(CentralConfig.AuditConfig.validated)

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))

  given DeriveConfig[SecretKey] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))
    .map(bytes => SecretKeySpec(bytes, "AES"))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[Json.Obj] = DeriveConfig[String]
    .mapOrFail(_.fromJson[Json.Obj].left.map(message => zio.Config.Error.InvalidData(message = message)))

  given DeriveConfig[versola.central.configuration.edges.EdgeId] =
    DeriveConfig[String].map(versola.central.configuration.edges.EdgeId(_))

  given DeriveConfig[versola.central.configuration.clients.ClientId] =
    DeriveConfig[String].mapOrFail: str =>
      versola.central.configuration.clients.ClientId.from(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))
