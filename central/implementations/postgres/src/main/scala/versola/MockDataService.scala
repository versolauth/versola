package versola

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.CreateClientRequest
import versola.central.configuration.challenges.{ChallengeSettingsService, OtpChallengeService}
import versola.central.configuration.clients.{ClientAlreadyExists, OAuthClientService}
import versola.central.configuration.edges.EdgeRepository
import versola.central.configuration.permissions.PermissionRepository
import versola.central.configuration.resources.{ResourceEndpointId, ResourceEndpointRecord, ResourceId, ResourceRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.OAuthScopeRepository
import versola.central.configuration.tenants.TenantRepository
import versola.util.SecureRandom
import zio.{Task, ZIO, ZLayer}

import scala.io.Source

trait MockDataService:
  def insert(): Task[Unit]

object MockDataService:
  val live: ZLayer[
    TenantRepository & PermissionRepository & ResourceRepository & OAuthScopeRepository & RoleRepository & OAuthClientService & OtpChallengeService & ChallengeSettingsService & EdgeRepository & TransactorZIO,
    Throwable,
    MockDataService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _))
      >>> ZLayer(ZIO.serviceWithZIO[MockDataService](service => service.insert().as(service)))

  final case class Impl(
      xa: TransactorZIO,
      tenantRepository: TenantRepository,
      permissionRepository: PermissionRepository,
      resourceRepository: ResourceRepository,
      scopeRepository: OAuthScopeRepository,
      roleRepository: RoleRepository,
      clientService: OAuthClientService,
      otpChallengeService: OtpChallengeService,
      challengeSettingsService: ChallengeSettingsService,
      edgeRepository: EdgeRepository,
  ) extends MockDataService:

    private def cleanup(): Task[Unit] =
      xa.connect:
        // Include themes explicitly so CASCADE from tenants does not silently wipe it.
        sql"""TRUNCATE TABLE tenants, permissions, resources, oauth_scopes, roles, oauth_clients, edges, themes RESTART IDENTITY CASCADE""".update.run()

    private def insertDefaultTheme(): Task[Unit] =
      ZIO.blocking:
        ZIO.attemptBlocking:
          val src = Source.fromResource("forms/common.css")
          try src.mkString finally src.close()
      .flatMap: css =>
        xa.connect:
          sql"""INSERT INTO themes (id, css, tenant_id) VALUES ('default', $css, NULL)""".update.run()
      .unit

    private def insertDefaultOtpTemplate(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.otpTemplates)(otpChallengeService.upsertTemplate)

    private def insertChallengeSettings(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.challengeSettings)(challengeSettingsService.upsertSettings)

    override def insert(): Task[Unit] =
      cleanup() *> insertDefaultTheme() *> insertTenants() *> insertDefaultOtpTemplate() *> insertChallengeSettings() *> insertResources() *> insertPermissions() *> insertScopes() *> insertRoles() *> insertClients() *> insertEdges()

    private def insertTenants(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.tenants): tenant =>
        tenantRepository.createTenant(tenant.id, tenant.description, None)

    private def insertPermissions(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.permissionDefinitions): permission =>
        permissionRepository.createPermission(
          tenantId = Some(permission.tenantId),
          permission = permission.id,
          description = permission.description,
          endpointIds = permission.endpointIds,
        )

    private def insertResources(): Task[Vector[ResourceId]] =
      ZIO.foreach(CentralMockData.resources): resource =>
        resourceRepository.createResource(
          tenantId = resource.tenantId,
          alias = resource.alias,
          resource = resource.resource,
          endpoints = resource.endpoints.map { endpoint =>
            ResourceEndpointRecord(
              id = endpoint.id,
              method = endpoint.method,
              path = endpoint.path,
              fetchUserInfo = endpoint.fetchUserInfo,
              allowExpression = endpoint.allow,
              inject = endpoint.inject,
            )
          },
        )

    private def insertScopes(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.scopes): scope =>
        scopeRepository.createScope(
          tenantId = scope.tenantId,
          id = scope.id,
          description = scope.description,
          claims = scope.claims,
        )

    private def insertRoles(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.roles): role =>
        roleRepository.createRole(
          tenantId = role.tenantId,
          id = role.id,
          description = role.description,
          permissions = role.permissions,
        ) *> ZIO.when(!role.active)(roleRepository.markRoleInactive(role.tenantId, role.id))

    private def insertClients(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.clients): client =>
        for
          _ <- clientService.registerClient(
            CreateClientRequest(
              tenantId = client.tenantId,
              id = client.id,
              clientName = client.clientName,
              redirectUris = client.redirectUris,
              allowedScopes = client.scope,
              audience = client.audience,
              permissions = client.permissions,
              accessTokenTtl = client.accessTokenTtl,
              refreshTokenTtl = Some(7776000),
              theme = client.theme,
              authFlow = client.authFlow,
              otpTemplateId = client.otpTemplateId,
            ),
          ).mapError {
            case e: ClientAlreadyExists => new IllegalStateException(s"Client ${e.clientId} already exists")
            case e: Throwable => e
          }
          _ <- ZIO.when(client.hasPreviousSecret)(clientService.rotateClientSecret(client.id).unit)
        yield ()

    private def insertEdges(): Task[Unit] =
      ZIO.foreachDiscard(CentralMockData.edges): edge =>
        edgeRepository.createEdge(edge.id, edge.publicKeyJwk)
