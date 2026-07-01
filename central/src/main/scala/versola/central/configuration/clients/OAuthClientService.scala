package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.{OAuthScopeRepository, ScopeToken}
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{CreateClientRequest, UpdateClientRequest}
import versola.util.{ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*

trait OAuthClientService:

  def getAllClients: Task[Vector[OAuthClientRecord]]

  def getClientsForSync(edgeId: Option[EdgeId]): Task[Vector[OAuthClientRecord]]

  def getTenantClients(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[OAuthClientRecord]]

  def registerClient(
      request: CreateClientRequest,
  ): IO[ClientAlreadyExists | Throwable, Secret]

  def updateClient(
      request: UpdateClientRequest,
  ): Task[Unit]

  def rotateClientSecret(clientId: ClientId): Task[Secret]

  def deletePreviousClientSecret(clientId: ClientId): Task[Unit]

  def deleteClient(clientId: ClientId): Task[Unit]

  def sync(event: SyncEvent.ClientsUpdated): Task[Unit]

object OAuthClientService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[Scope & OAuthClientRepository & TenantRepository & SecureRandom & SecurityService & CentralConfig, Throwable, OAuthClientService] =
    ZLayer(ReloadingCache.make[Vector[OAuthClientRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  case class Impl(
      cache: ReloadingCache[Vector[OAuthClientRecord]],
      clientRepository: OAuthClientRepository,
      tenantRepository: TenantRepository,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      config: CentralConfig,
  ) extends OAuthClientService:

    override def getAllClients: Task[Vector[OAuthClientRecord]] =
      cache.get

    override def getClientsForSync(edgeId: Option[EdgeId]): Task[Vector[OAuthClientRecord]] =
      edgeId match
        case None => cache.get
        case Some(id) =>
          for
            clients <- cache.get
            tenants <- tenantRepository.getAll
            allowedTenantIds = tenants.filter(_.edgeId.contains(id)).map(_.id).toSet
          yield clients.filter(c => allowedTenantIds.contains(c.tenantId))

    override def getTenantClients(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[OAuthClientRecord]] =
      cache.get.map { records =>
        records
          .filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def registerClient(
        request: CreateClientRequest,
    ): IO[ClientAlreadyExists | Throwable, Secret] =
      for
        secret <- generateSecret
        encryptedSecret <- encryptRawSecret(secret)
        client = OAuthClientRecord(
          id = request.id,
          tenantId = request.tenantId,
          clientName = request.clientName,
          redirectUris = request.redirectUris,
          scope = request.allowedScopes,
          externalAudience = request.audience,
          secret = Some(encryptedSecret),
          previousSecret = None,
          accessTokenTtl = Duration.fromSeconds(request.accessTokenTtl),
          refreshTokenTtl = Duration.fromSeconds(request.refreshTokenTtl.getOrElse(7776000)),
          permissions = request.permissions,
          theme = request.theme,
          authFlow = request.authFlow,
          otpTemplateId = request.otpTemplateId,
        )
        _ <- clientRepository.createClient(client)
      yield secret

    override def updateClient(
        request: UpdateClientRequest,
    ): Task[Unit] =
      clientRepository.updateClient(
        clientId = request.clientId,
        clientName = request.clientName,
        patchRedirectUris = request.redirectUris,
        patchScope = request.scope,
        patchPermissions = request.permissions,
        accessTokenTtl = request.accessTokenTtl.map(Duration.fromSeconds),
        refreshTokenTtl = request.refreshTokenTtl.map(Duration.fromSeconds),
        theme = request.theme,
        authFlow = request.authFlow,
        otpTemplateId = request.otpTemplateId,
      )

    override def rotateClientSecret(clientId: ClientId): Task[Secret] =
      for
        newSecret <- generateSecret
        encryptedSecret <- encryptRawSecret(newSecret)
        _ <- clientRepository.rotateClientSecret(clientId, encryptedSecret)
      yield newSecret

    override def deletePreviousClientSecret(clientId: ClientId): Task[Unit] =
      clientRepository.deletePreviousClientSecret(clientId)

    override def deleteClient(clientId: ClientId): Task[Unit] =
      clientRepository.deleteClient(clientId)

    override def sync(event: SyncEvent.ClientsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        clientRepository.find(event.id),
      )

    private val clientSecretsKey: javax.crypto.SecretKey =
      javax.crypto.spec.SecretKeySpec(config.clientSecretsSecret, "AES")

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    private def encryptRawSecret(secret: Secret): Task[Secret] =
      securityService.encryptAes256(secret, clientSecretsKey).map(Secret(_))
