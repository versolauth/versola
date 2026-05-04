package versola

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.CreateClientRequest
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.permissions.PermissionRepository
import versola.central.configuration.resources.{ResourceEndpointId, ResourceEndpointRecord, ResourceId, ResourceRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.OAuthScopeRepository
import versola.central.configuration.tenants.TenantRepository
import versola.util.SecureRandom
import zio.{Task, ZIO, ZLayer}

trait MockDataService:
  def insert(): Task[Unit]

object MockDataService:
  val live: ZLayer[
    TenantRepository & PermissionRepository & ResourceRepository & OAuthScopeRepository & RoleRepository & OAuthClientService & TransactorZIO,
    Throwable,
    MockDataService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))
      >>> ZLayer(ZIO.serviceWithZIO[MockDataService](service => service.insert().as(service)))

  final case class Impl(
      xa: TransactorZIO,
      tenantRepository: TenantRepository,
      permissionRepository: PermissionRepository,
      resourceRepository: ResourceRepository,
      scopeRepository: OAuthScopeRepository,
      roleRepository: RoleRepository,
      clientService: OAuthClientService
  ) extends MockDataService:

    private def cleanup(): Task[Unit] =
      xa.connect:
        sql"""TRUNCATE TABLE tenants, permissions, resources, oauth_scopes, roles, oauth_clients, edges RESTART IDENTITY CASCADE""".update.run()

    override def insert(): Task[Unit] =
      cleanup() *> insertTenants() *> insertResources() *> insertPermissions() *> insertScopes() *> insertRoles() *> insertClients()

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
          resource = resource.resource,
          endpoints = resource.endpoints.map { endpoint =>
            ResourceEndpointRecord(
              id = endpoint.id,
              method = endpoint.method,
              path = endpoint.path,
              fetchUserInfo = endpoint.fetchUserInfo,
              allowRules = endpoint.allowRules,
              denyRules = endpoint.denyRules,
              injectHeaders = endpoint.injectHeaders,
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
        clientService.registerClient(
          CreateClientRequest(
            tenantId = client.tenantId,
            id = client.id,
            clientName = client.clientName,
            redirectUris = client.redirectUris,
            allowedScopes = client.scope,
            audience = client.audience,
            permissions = client.permissions,
            accessTokenTtl = client.accessTokenTtl,
          ),
        ) *> ZIO.when(client.hasPreviousSecret)(clientService.rotateClientSecret(client.tenantId, client.id).unit)
