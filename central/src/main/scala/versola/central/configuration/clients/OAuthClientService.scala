package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.{OAuthScopeRepository, ScopeToken}
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{ConsentFlowDto, CreateClientRequest, UpdateClientRequest}
import versola.util.{CacheSource, Patch, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.{Scheme, URL}

import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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
      presetSecret: Option[Secret] = None,
  ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, Secret]

  def updateClient(
      request: UpdateClientRequest,
  ): IO[InvalidRegistrationConfiguration | Throwable, Unit]

  def rotateClientSecret(clientId: ClientId): Task[Secret]

  def deletePreviousClientSecret(clientId: ClientId): Task[Unit]

  def deleteClient(clientId: ClientId): Task[Unit]

  def sync(event: SyncEvent.ClientsUpdated): Task[Unit]

  /** Verifies that `provided` matches the current or previous secret of the
    * `central-admin` OAuth client (for secret rotation support). Both comparisons
    * are constant-time to prevent timing attacks.
    */
  def verifySecret(provided: Secret): Task[Boolean]

object OAuthClientService:
  def live: ZLayer[Scope & OAuthClientRepository & TenantRepository & RoleRepository & SecureRandom & SecurityService & CentralConfig, Throwable, OAuthClientService] =
    decryptingCacheSource >>>
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[CentralConfig](config =>
          ReloadingCache.make[Vector[OAuthClientRecord]](config.configurationCacheRefreshInterval),
        )
      ) >>>
      ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  /** A [[CacheSource]] that reads the client records from the
    * repository and decrypts their secrets, so the in-memory cache holds plaintext
    * secrets and no decryption is needed on cache reads.
    */
  private val decryptingCacheSource
      : URLayer[OAuthClientRepository & SecurityService & CentralConfig, CacheSource[Vector[OAuthClientRecord]]] =
    ZLayer.fromFunction: (repository: OAuthClientRepository, securityService: SecurityService, config: CentralConfig) =>
      new CacheSource[Vector[OAuthClientRecord]]:
        override def getAll: Task[Vector[OAuthClientRecord]] =
          repository.getAll.flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, clientSecretsKey(config))))

  private def clientSecretsKey(config: CentralConfig): SecretKey =
    SecretKeySpec(config.clientSecretsSecret, "AES")

  /** Decrypts the at-rest encrypted `secret` and `previousSecret` of a client record. */
  private def decryptSecrets(
      record: OAuthClientRecord,
      securityService: SecurityService,
      key: SecretKey,
  ): Task[OAuthClientRecord] =
    for
      secret         <- ZIO.foreach(record.secret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      previousSecret <- ZIO.foreach(record.previousSecret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
    yield record.copy(secret = secret, previousSecret = previousSecret)

  case class Impl(
      cache: ReloadingCache[Vector[OAuthClientRecord]],
      clientRepository: OAuthClientRepository,
      tenantRepository: TenantRepository,
      roleRepository: RoleRepository,
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
        presetSecret: Option[Secret] = None,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, Secret] =
      for
        _ <- validateConsentUris(
          "logoUri" -> request.logoUri,
          "policyUri" -> request.policyUri,
          "tosUri" -> request.tosUri,
        )
        _ <- validateRegistration(request.id, request.tenantId, request.authFlow, request.registrationFlow)
        secret <- presetSecret.fold(generateSecret)(ZIO.succeed(_))
        encryptedSecret <- encryptRawSecret(secret)
        client = OAuthClientRecord(
          id = request.id,
          tenantId = request.tenantId,
          clientName = request.clientName,
          redirectUris = request.redirectUris,
          scope = request.allowedScopes,
          secret = Some(encryptedSecret),
          previousSecret = None,
          accessTokenTtl = Duration.fromSeconds(request.accessTokenTtl),
          refreshTokenTtl = Duration.fromSeconds(request.refreshTokenTtl.getOrElse(7776000)),
          permissions = request.permissions,
          theme = request.theme,
          authFlow = request.authFlow,
          registrationFlow = request.registrationFlow,
          otpTemplateId = request.otpTemplateId,
          frontChannelLogoutUri = request.frontChannelLogoutUri.flatMap(URL.decode(_).toOption),
          frontChannelLogoutSessionRequired = request.frontChannelLogoutSessionRequired,
          backChannelLogoutUri = request.backChannelLogoutUri.flatMap(URL.decode(_).toOption),
          logoUri = request.logoUri,
          policyUri = request.policyUri,
          tosUri = request.tosUri,
          consentFlow = request.consentFlow.map(_.toDomain),
        )
        _ <- clientRepository.createClient(client)
      yield secret

    override def updateClient(
        request: UpdateClientRequest,
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      for
        _ <- validateConsentUris(
          "logoUri" -> request.logoUri.flatMap(patchValue),
          "policyUri" -> request.policyUri.flatMap(patchValue),
          "tosUri" -> request.tosUri.flatMap(patchValue),
        )
        current <- cache.get.map(_.find(_.id == request.clientId))
        _ <- ZIO.foreachDiscard(current): client =>
          validateRegistration(
            clientId = request.clientId,
            tenantId = client.tenantId,
            authFlow = request.authFlow.applyTo(client.authFlow),
            registrationFlow = request.registrationFlow.applyTo(client.registrationFlow),
          )
        _ <- clientRepository.updateClient(
          clientId = request.clientId,
          clientName = request.clientName,
          patchRedirectUris = request.redirectUris,
          patchScope = request.scope,
          patchPermissions = request.permissions,
          accessTokenTtl = request.accessTokenTtl.map(Duration.fromSeconds),
          refreshTokenTtl = request.refreshTokenTtl.map(Duration.fromSeconds),
          theme = request.theme,
          authFlow = request.authFlow,
          registrationFlow = request.registrationFlow,
          otpTemplateId = request.otpTemplateId,
          frontChannelLogoutUri = request.frontChannelLogoutUri.map(decodeUrlPatch),
          frontChannelLogoutSessionRequired = request.frontChannelLogoutSessionRequired,
          backChannelLogoutUri = request.backChannelLogoutUri.map(decodeUrlPatch),
          logoUri = request.logoUri,
          policyUri = request.policyUri,
          tosUri = request.tosUri,
          consentFlow = request.consentFlow.map(toConsentFlowPatch),
        )
      yield ()

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
        clientRepository.find(event.id).flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, clientSecretsKey))),
      )

    override def verifySecret(provided: Secret): Task[Boolean] =
      cache.get.map: clients =>
        clients.find(_.id == CentralConfig.centralClientId).exists: client =>
          client.secret.exists(MessageDigest.isEqual(provided, _)) ||
            client.previousSecret.exists(MessageDigest.isEqual(provided, _))

    /** Rejects a registration flow the auth service could not run: one without a usable
      * entry credential, or one granting roles that do not exist in the client's tenant.
      */
    private def validateRegistration(
        clientId: ClientId,
        tenantId: TenantId,
        authFlow: Option[AuthFlow],
        registrationFlow: Option[RegistrationFlow],
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      for
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validate(clientId, authFlow, registrationFlow))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(registrationFlow): flow =>
          ZIO.foreachDiscard(flow.roleIds): roleId =>
            roleRepository.findRole(tenantId, roleId).someOrFail:
              InvalidRegistrationConfiguration(clientId, s"role '$roleId' does not exist in tenant '$tenantId'")
      yield ()

    /** An unparsable URI clears the column, matching how create drops undecodable URIs. */
    private def decodeUrlPatch(patch: Patch[String]): Patch[URL] = patch match
      case Patch.Deleted     => Patch.Deleted
      case Patch.Modified(v) => URL.decode(v).toOption.fold(Patch.Deleted)(Patch.Modified(_))

    private def toConsentFlowPatch(patch: Patch[ConsentFlowDto]): Patch[ConsentFlow] = patch match
      case Patch.Deleted        => Patch.Deleted
      case Patch.Modified(flow) => Patch.Modified(flow.toDomain)

    private def patchValue(patch: Patch[String]): Option[String] = patch match
      case Patch.Deleted     => None
      case Patch.Modified(v) => Some(v)

    private def validateConsentUris(
        values: (String, Option[String])*,
    ): IO[InvalidConsentUri | Throwable, Unit] =
      ZIO.foreachDiscard(values): (field, value) =>
        value.fold[IO[InvalidConsentUri | Throwable, Unit]](ZIO.unit): uri =>
          URL.decode(uri.trim) match
            case Left(_) =>
              ZIO.fail(InvalidConsentUri(field, "must be an absolute HTTPS URL"))
            case Right(url)
                if !url.isAbsolute || url.scheme != Some(Scheme.HTTPS) || url.host.isEmpty =>
              ZIO.fail(InvalidConsentUri(field, "must be an absolute HTTPS URL"))
            case Right(_) => ZIO.unit

    private val clientSecretsKey: SecretKey = OAuthClientService.clientSecretsKey(config)

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    private def encryptRawSecret(secret: Secret): Task[Secret] =
      securityService.encryptAes256(secret, clientSecretsKey).map(Secret(_))
