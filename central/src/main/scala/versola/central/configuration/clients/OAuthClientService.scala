package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.{OAuthScopeRepository, ScopeToken}
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{ConsentFlowDto, CreateClientRequest, UpdateClientRequest}
import versola.util.{CacheSource, Patch, PrivateJsonWebKey, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.{Scheme, URL}

import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json

import java.nio.charset.StandardCharsets

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

  /** Returns the generated secret for a `web` client, and `None` for a `native` one -
    * a public client is registered without a secret and can never be given one.
    */
  def registerClient(
      request: CreateClientRequest,
      presetSecret: Option[Secret] = None,
  ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, Option[Secret]]

  def updateClient(
      request: UpdateClientRequest,
  ): IO[InvalidRegistrationConfiguration | Throwable, Unit]

  def rotateClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Secret]

  def deletePreviousClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit]

  def deleteClient(clientId: ClientId): Task[Unit]

  def sync(event: SyncEvent.ClientsUpdated): Task[Unit]

  /** Verifies that `provided` matches the current or previous secret of the
    * `central-admin` OAuth client (for secret rotation support). Both comparisons
    * are constant-time to prevent timing attacks.
    */
  def verifySecret(provided: Secret): Task[Boolean]

object OAuthClientService:
  def live: ZLayer[Scope & OAuthClientRepository & TenantRepository & RoleRepository & ChallengeSettingsService & SecureRandom & SecurityService & CentralConfig, Throwable, OAuthClientService] =
    decryptingCacheSource >>>
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[CentralConfig](config =>
          ReloadingCache.make[Vector[OAuthClientRecord]](config.configurationCacheRefreshInterval),
        )
      ) >>>
      ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _))

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

  /** Decrypts the at-rest encrypted `secret`, `previousSecret` and `edgeSigningKey` of a
    * client record. */
  private def decryptSecrets(
      record: OAuthClientRecord,
      securityService: SecurityService,
      key: SecretKey,
  ): Task[OAuthClientRecord] =
    for
      secret         <- ZIO.foreach(record.secret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      previousSecret <- ZIO.foreach(record.previousSecret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      edgeSigningKey <- ZIO.foreach(record.edgeSigningKey)(s => securityService.decryptAes256(s, key).map(Secret(_)))
    yield record.copy(secret = secret, previousSecret = previousSecret, edgeSigningKey = edgeSigningKey)

  case class Impl(
      cache: ReloadingCache[Vector[OAuthClientRecord]],
      clientRepository: OAuthClientRepository,
      tenantRepository: TenantRepository,
      roleRepository: RoleRepository,
      challengeSettingsService: ChallengeSettingsService,
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
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, Option[Secret]] =
      for
        _ <- validateConsentUris(
          "logoUri" -> request.logoUri,
          "policyUri" -> request.policyUri,
          "tosUri" -> request.tosUri,
        )
        frontChannelLogoutUrl <- validateLogoutUri("frontChannelLogoutUri", request.frontChannelLogoutUri)
        backChannelLogoutUrl <- validateLogoutUri("backChannelLogoutUri", request.backChannelLogoutUri)
        _ <- validateRegistration(request.id, request.tenantId, request.authFlow, request.registrationFlow)
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateAccessTokenTtl(
          request.id,
          Duration.fromSeconds(request.accessTokenTtl),
          request.dpopBoundAccessTokens,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateClientAuthentication(
          request.id,
          request.mtlsAuth,
          request.jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateRequestObjectRequirement(
          request.id,
          request.requireSignedRequestObject,
          request.jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeSigningKey(
          request.id,
          request.edgeSigningKey,
          request.mtlsAuth,
          request.jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateDpopKeyPolicy(
          request.id,
          request.dpopMinRsaKeySize,
        ))(ZIO.fail(_))
        _ <- validateMtlsTermination(request.id, request.tenantId, request.mtlsAuth)
        secret <- request.clientType match
          case ClientType.web    => presetSecret.fold(generateSecret)(ZIO.succeed(_)).asSome
          case ClientType.native => ZIO.none
        encryptedSecret <- ZIO.foreach(secret)(encryptRawSecret)
        encryptedEdgeSigningKey <- ZIO.foreach(request.edgeSigningKey)(encryptEdgeSigningKey)
        client = OAuthClientRecord(
          id = request.id,
          tenantId = request.tenantId,
          clientName = request.clientName,
          redirectUris = request.redirectUris,
          scope = request.allowedScopes,
          secret = encryptedSecret,
          previousSecret = None,
          accessTokenTtl = Duration.fromSeconds(request.accessTokenTtl),
          refreshTokenTtl = Duration.fromSeconds(request.refreshTokenTtl.getOrElse(7776000)),
          permissions = request.permissions,
          theme = request.theme,
          authFlow = request.authFlow,
          registrationFlow = request.registrationFlow,
          otpTemplateId = request.otpTemplateId,
          frontChannelLogoutUri = frontChannelLogoutUrl,
          frontChannelLogoutSessionRequired = request.frontChannelLogoutSessionRequired,
          backChannelLogoutUri = backChannelLogoutUrl,
          logoUri = request.logoUri,
          policyUri = request.policyUri,
          tosUri = request.tosUri,
          consentFlow = request.consentFlow.map(_.toDomain),
          dpopBoundAccessTokens = request.dpopBoundAccessTokens,
          dpopSigningAlgs = request.dpopSigningAlgs,
          dpopMinRsaKeySize = request.dpopMinRsaKeySize,
          mtlsAuth = request.mtlsAuth.map(normaliseMtlsAuth),
          certificateBoundAccessTokens = request.certificateBoundAccessTokens,
          jwks = request.jwks,
          requireSignedRequestObject = request.requireSignedRequestObject,
          requirePushedAuthorizationRequests = request.requirePushedAuthorizationRequests,
          edgeSigningKey = encryptedEdgeSigningKey,
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
        _ <- validateLogoutUri("frontChannelLogoutUri", request.frontChannelLogoutUri.flatMap(patchValue))
        _ <- validateLogoutUri("backChannelLogoutUri", request.backChannelLogoutUri.flatMap(patchValue))
        current <- cache.get.map(_.find(_.id == request.clientId))
        edgeSigningKey <- ZIO.foreach(current)(effectiveEdgeSigningKey(request, _)).map(_.flatten)
        _ <- ZIO.foreachDiscard(current): client =>
          validateRegistration(
            clientId = request.clientId,
            tenantId = client.tenantId,
            authFlow = request.authFlow.applyTo(client.authFlow),
            registrationFlow = request.registrationFlow.applyTo(client.registrationFlow),
          ) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateAccessTokenTtl(
            clientId = request.clientId,
            accessTokenTtl = request.accessTokenTtl.map(Duration.fromSeconds).getOrElse(client.accessTokenTtl),
            dpopBoundAccessTokens = request.dpopBoundAccessTokens.getOrElse(client.dpopBoundAccessTokens),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateClientAuthentication(
            clientId = request.clientId,
            mtlsAuth = request.mtlsAuth.applyTo(client.mtlsAuth),
            jwks = request.jwks.applyTo(client.jwks),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateRequestObjectRequirement(
            clientId = request.clientId,
            requireSignedRequestObject =
              request.requireSignedRequestObject.getOrElse(client.requireSignedRequestObject),
            jwks = request.jwks.applyTo(client.jwks),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeSigningKey(
            clientId = request.clientId,
            edgeSigningKey = edgeSigningKey,
            mtlsAuth = request.mtlsAuth.applyTo(client.mtlsAuth),
            jwks = request.jwks.applyTo(client.jwks),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateDpopKeyPolicy(
            clientId = request.clientId,
            dpopMinRsaKeySize = request.dpopMinRsaKeySize.applyTo(client.dpopMinRsaKeySize),
          ))(ZIO.fail(_)) *> validateMtlsTermination(
            request.clientId,
            client.tenantId,
            request.mtlsAuth.applyTo(client.mtlsAuth),
          )
        edgeSigningKeyPatch <- ZIO.foreach(request.edgeSigningKey):
          case Patch.Modified(key) => encryptEdgeSigningKey(key).map(Patch.Modified(_))
          case Patch.Deleted => ZIO.succeed(Patch.Deleted)
        _ <- clientRepository.updateClient(
          request.clientId,
          OAuthClientPatch(
            clientName = request.clientName,
            redirectUris = request.redirectUris,
            scope = request.scope,
            permissions = request.permissions,
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
            dpopBoundAccessTokens = request.dpopBoundAccessTokens,
            dpopSigningAlgs = request.dpopSigningAlgs,
            dpopMinRsaKeySize = request.dpopMinRsaKeySize,
            mtlsAuth = request.mtlsAuth.map(toMtlsAuthPatch),
            certificateBoundAccessTokens = request.certificateBoundAccessTokens,
            jwks = request.jwks,
            requireSignedRequestObject = request.requireSignedRequestObject,
            requirePushedAuthorizationRequests = request.requirePushedAuthorizationRequests,
            edgeSigningKey = edgeSigningKeyPatch,
          ),
        )
      yield ()

    override def rotateClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Secret] =
      for
        _ <- rejectPublicClient(clientId)
        newSecret <- generateSecret
        encryptedSecret <- encryptRawSecret(newSecret)
        _ <- clientRepository.rotateClientSecret(clientId, encryptedSecret)
      yield newSecret

    override def deletePreviousClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit] =
      rejectPublicClient(clientId) *> clientRepository.deletePreviousClientSecret(clientId)

    /** Reads the client from the repository rather than the cache: a client registered a
      * moment ago may not have reached the cache yet, and a stale miss would let a public
      * client through. An unknown client is left to the repository, which ignores it.
      */
    private def rejectPublicClient(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit] =
      clientRepository.find(clientId).flatMap: client =>
        ZIO.fail(ClientHasNoSecret(clientId)).when(client.exists(_.isPublic)).unit

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

    /** Reads the tenant's challenge settings only when there is an `mtlsAuth` to justify it:
      * every other registration would pay for a lookup whose answer it has no use for.
      */
    private def validateMtlsTermination(
        clientId: ClientId,
        tenantId: TenantId,
        mtlsAuth: Option[MutualTlsAuth],
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      ZIO.when(mtlsAuth.nonEmpty):
        challengeSettingsService.getSettings(tenantId).flatMap: settings =>
          ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateMtlsTermination(
            clientId,
            mtlsAuth,
            settings.flatMap(_.mtlsCertificateHeader),
          ))(ZIO.fail(_))
      .unit

    /** An unparsable URI clears the column, matching how create drops undecodable URIs.
      * Trims before parsing so this agrees with `validateLogoutUri`, which validates the
      * trimmed value - otherwise a value with leading/trailing whitespace could pass
      * validation here yet fail to parse untrimmed, silently clearing the column instead of
      * storing the validated URI.
      */
    private def decodeUrlPatch(patch: Patch[String]): Patch[URL] = patch match
      case Patch.Deleted     => Patch.Deleted
      case Patch.Modified(v) => URL.decode(v.trim).toOption.fold(Patch.Deleted)(Patch.Modified(_))

    /** RFC 8705 §2.1.2 compares the registered value against the certificate literally, so
      * surrounding whitespace an operator pastes in would silently stop every certificate
      * from matching. Trimming is the only normalisation applied: the RFC 4514 form of a
      * subject DN is otherwise significant, down to attribute order. §2.2 registers no
      * subject value at all, so there is nothing to normalise for it. */
    private def normaliseMtlsAuth(auth: MutualTlsAuth): MutualTlsAuth =
      auth match
        case tls: MutualTlsAuth.TlsClientAuth =>
          tls.copy(subjectValue = tls.subjectValue.trim)
        case MutualTlsAuth.SelfSignedTlsClientAuth() =>
          auth

    private def toMtlsAuthPatch(patch: Patch[MutualTlsAuth]): Patch[MutualTlsAuth] = patch match
      case Patch.Deleted        => Patch.Deleted
      case Patch.Modified(auth) => Patch.Modified(normaliseMtlsAuth(auth))

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

    /** Unlike `logoUri`/`policyUri`/`tosUri` (browser-loaded consent links, HTTPS-only), a
      * logout notification URI may target `http://localhost` for local development - matching
      * the frontend's `validateLogoutUri`. A malformed or disallowed value is rejected outright
      * rather than silently dropped, so an API caller cannot end up with a client that looks
      * configured but has no working logout notification.
      */
    private def validateLogoutUri(field: String, value: Option[String]): IO[InvalidConsentUri | Throwable, Option[URL]] =
      value.fold[IO[InvalidConsentUri | Throwable, Option[URL]]](ZIO.none): raw =>
        val invalid = ZIO.fail(InvalidConsentUri(field, "must be an absolute https:// URL (or http://localhost for local development)"))
        URL.decode(raw.trim) match
          case Left(_) => invalid
          case Right(url) if !url.isAbsolute || url.host.isEmpty || url.fragment.isDefined => invalid
          case Right(url) if url.scheme == Some(Scheme.HTTPS) => ZIO.some(url)
          case Right(url) if url.scheme == Some(Scheme.HTTP) && url.host.exists(h => h == "localhost" || h == "127.0.0.1") => ZIO.some(url)
          case Right(_) => invalid

    private val clientSecretsKey: SecretKey = OAuthClientService.clientSecretsKey(config)

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    private def encryptRawSecret(secret: Secret): Task[Secret] =
      securityService.encryptAes256(secret, clientSecretsKey).map(Secret(_))

    /** The private JWK as it is stored: the document's bytes under the same at-rest key as the
      * secret, so one rotation of `clientSecretsSecret` covers both. Encoded UTF-8 rather than
      * re-serialized from a parsed key, which would drop JWK members central has no opinion
      * on — the same reason [[PrivateJsonWebKey]] keeps the document. */
    private def encryptEdgeSigningKey(key: PrivateJsonWebKey): Task[Secret] =
      encryptRawSecret(Secret(key.document.toJson.getBytes(StandardCharsets.UTF_8)))

    /** The signing key the client will hold once this patch is applied, which is the stored one
      * whenever the patch does not name it.
      *
      * A patch that leaves the key alone can still invalidate it: replacing or deleting `jwks`
      * takes away the public half auth verifies against, and validating only what the request
      * carries would let that through and break every key-authenticated login for the client.
      *
      * The stored document is read back only in that case. A patch that replaces or deletes the
      * key never parses it, so a client whose stored key somehow cannot be read is still one an
      * operator can repair rather than one locked out of its own registration.
      */
    private def effectiveEdgeSigningKey(
        request: UpdateClientRequest,
        client: OAuthClientRecord,
    ): Task[Option[PrivateJsonWebKey]] =
      request.edgeSigningKey match
        case Some(Patch.Modified(key)) => ZIO.some(key)
        case Some(Patch.Deleted) => ZIO.none
        case None =>
          ZIO.foreach(client.edgeSigningKey): stored =>
            ZIO.fromEither(String(stored, StandardCharsets.UTF_8).fromJson[Json.Obj])
              .mapBoth(
                error => RuntimeException(s"stored edgeSigningKey of ${client.id} is not a JSON object: $error"),
                PrivateJsonWebKey(_),
              )
