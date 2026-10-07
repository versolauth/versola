package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, SecurityProfile}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.scopes.{OAuthScopeRepository, ScopeToken}
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{ConsentFlowDto, CreateClientRequest, UpdateClientRequest}
import versola.util.{CacheSource, EnvName, Patch, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.{Scheme, URL}

import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json

import java.nio.charset.StandardCharsets

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** What a registration produced. [[createdAt]] is the time central recorded, not the time
  * the caller asked: a console that has just registered a client would otherwise have to
  * read it back, or date the client by its own clock, to say how old it is. */
case class RegisteredClient(
    secret: Option[Secret],
    createdAt: Instant,
    /** The `private_key_jwt` key generated for this registration, where `generateJwks` asked
      * for one. Returned rather than stored, so this is the only time it can be read. */
    privateKey: Option[PrivateJsonWebKey],
)

trait OAuthClientService:

  def getAllClients: Task[Vector[OAuthClientRecord]]

  def getClientsForSync(edgeId: Option[EdgeId]): Task[Vector[OAuthClientRecord]]

  def getTenantClients(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[OAuthClientRecord]]

  /** Returns the generated secret for a client that registered [[AuthMethod.client_secret]],
    * and `None` for every other method - a client whose credential is a certificate, a key
    * set, or nothing at all is registered without a secret, and issuing one anyway would
    * leave a credential lying in the database that no endpoint would ever accept. The
    * registration time comes back with it: it is settled here, and nowhere else.
    */
  def registerClient(
      request: CreateClientRequest,
      presetSecret: Option[Secret] = None,
      enforceSecurityProfile: Boolean = true,
  ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient]

  /** @param enforceSecurityProfile holds the client, as the patch leaves it, to its tenant's
    *                               [[SecurityProfile]]. Only bootstrap turns it off, and only
    *                               for a seeded client it cannot yet make conformant -- every
    *                               API caller is held to the profile. */
  def updateClient(
      request: UpdateClientRequest,
      enforceSecurityProfile: Boolean = true,
  ): IO[InvalidRegistrationConfiguration | Throwable, Unit]

  def rotateClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Secret]

  def deletePreviousClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit]

  def deleteClient(clientId: ClientId): Task[Unit]

  def sync(event: SyncEvent.ClientsUpdated): Task[Unit]

  /** Reloads the whole cache from the repository now, instead of at the next scheduled
    * refresh. For bootstrap: this cache is loaded before bootstrap seeds `central-admin`, and
    * the `client_change` notifications the seed fires go out before central starts listening
    * for them -- so without a reload the seeded client is missing from `/configuration/clients/sync`,
    * and its presets from the edge-filtered `/configuration/auth-request-presets/sync`, until
    * that refresh: `/login/central-admin` answers 404 for up to an interval after a fresh deploy.
    */
  def refreshNow: Task[Unit]

  /** Verifies that `provided` matches the current or previous secret of the
    * `central-admin` OAuth client (for secret rotation support). Both comparisons
    * are constant-time to prevent timing attacks.
    */
  def verifySecret(provided: Secret): Task[Boolean]

object OAuthClientService:
  def live: ZLayer[Scope & OAuthClientRepository & TenantRepository & RoleRepository & ChallengeSettingsService & SecureRandom & SecurityService & ClientCertificateAuthority & CentralConfig & EnvName, Throwable, OAuthClientService] =
    decryptingCacheSource >>>
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[CentralConfig](config =>
          ReloadingCache.make[Vector[OAuthClientRecord]](config.configurationCacheRefreshInterval),
        )
      ) >>>
      ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _))

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

  /** Decrypts the at-rest encrypted `secret`, `previousSecret`, `edgeSigningKey` and
    * `edgeClientCertificate` of a client record. */
  private def decryptSecrets(
      record: OAuthClientRecord,
      securityService: SecurityService,
      key: SecretKey,
  ): Task[OAuthClientRecord] =
    for
      secret         <- ZIO.foreach(record.secret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      previousSecret <- ZIO.foreach(record.previousSecret)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      edgeSigningKey <- ZIO.foreach(record.edgeSigningKey)(s => securityService.decryptAes256(s, key).map(Secret(_)))
      edgeCertificate <- ZIO.foreach(record.edgeClientCertificate)(s =>
        securityService.decryptAes256(s, key).map(Secret(_)),
      )
    yield record.copy(
      secret = secret,
      previousSecret = previousSecret,
      edgeSigningKey = edgeSigningKey,
      edgeClientCertificate = edgeCertificate,
    )

  case class Impl(
      cache: ReloadingCache[Vector[OAuthClientRecord]],
      clientRepository: OAuthClientRepository,
      tenantRepository: TenantRepository,
      roleRepository: RoleRepository,
      challengeSettingsService: ChallengeSettingsService,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      certificateAuthority: ClientCertificateAuthority,
      config: CentralConfig,
      envName: EnvName,
  ) extends OAuthClientService:

    /** Outside production the local stack's own edge is served at `http://localhost`, and so is
      * every web client it fronts -- see [[InvalidRegistrationConfiguration.profileViolations]]. */
    private val allowHttpLoopback: Boolean = envName.isTest

    /** Last of the registration checks, so a client that could not work at all is told why
      * before it is told that its tenant would not admit it. */
    private def validateSecurityProfile(
        client: OAuthClientRecord,
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      challengeSettingsService.getSecurityProfile(client.tenantId).flatMap: profile =>
        ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateSecurityProfile(
          client.id,
          profile,
          InvalidRegistrationConfiguration.ProfileSubject.of(client),
          allowHttpLoopback,
        ))(ZIO.fail(_))

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
        enforceSecurityProfile: Boolean = true,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      for
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateIssuedEdgeClientCertificate(
          request.id,
          request.issueEdgeClientCertificate,
          request.authMethod,
          request.mtlsAuth,
          request.edgeClientCertificate,
        ))(ZIO.fail(_))
        // Issued first and then registered as if supplied, so the client is held to exactly the
        // rules one carrying its own certificate is.
        issued <- ZIO.when(request.issueEdgeClientCertificate):
          certificateAuthority.issue(request.tenantId, request.id)
            .catchSome { case ClientCertificateAuthority.Expired(at) =>
              ZIO.fail(InvalidRegistrationConfiguration.clientCertificateAuthorityExpired(request.id, at))
            }
            .someOrFail(InvalidRegistrationConfiguration.noClientCertificateAuthority(request.id))
        registered <- register(
          issued.fold(request)(certificate =>
            request.copy(
              mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, certificate.subjectDn)),
              edgeClientCertificate = Some(certificate.certificate),
            ),
          ),
          presetSecret,
          enforceSecurityProfile,
        )
      yield registered

    private def register(
        request: CreateClientRequest,
        presetSecret: Option[Secret],
        enforceSecurityProfile: Boolean,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      for
        _ <- validateConsentUris(
          "logoUri" -> request.logoUri,
          "policyUri" -> request.policyUri,
          "tosUri" -> request.tosUri,
        )
        _ <- validateRedirectUris(Some(request.tenantId), request.redirectUris)
        frontChannelLogoutUrl <- validateLogoutUri("frontChannelLogoutUri", request.frontChannelLogoutUri)
        backChannelLogoutUrl <- validateLogoutUri("backChannelLogoutUri", request.backChannelLogoutUri)
        _ <- validateRegistration(request.id, request.tenantId, request.authFlow, request.registrationFlow)
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateAccessTokenTtl(
          request.id,
          Duration.fromSeconds(request.accessTokenTtl),
          request.dpopBoundAccessTokens,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateGeneratedJwks(
          request.id,
          request.authMethod,
          request.jwks,
          request.generateJwks,
        ))(ZIO.fail(_))
        // Generated before the checks that read a key set, so that a registration asking for
        // a key is held to exactly the rules one supplying it is -- the request is short a
        // credential only until here, and nothing below can tell the two apart.
        generatedKey <- ZIO.foreach(request.generateJwks)(ClientKeyGeneration.generate(securityService, _))
        jwks = generatedKey.map(_.publicKeys).orElse(request.jwks)
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateClientAuthentication(
          request.id,
          request.authMethod,
          request.mtlsAuth,
          jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateRequestObjectRequirement(
          request.id,
          request.requireSignedRequestObject,
          jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeSigningKey(
          request.id,
          request.edgeSigningKey,
          request.mtlsAuth,
          jwks,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeClientCertificate(
          request.id,
          request.edgeClientCertificate,
          request.mtlsAuth.map(normaliseMtlsAuth),
          jwks,
          request.requireSignedRequestObject,
        ))(ZIO.fail(_))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateDpopKeyPolicy(
          request.id,
          request.dpopMinRsaKeySize,
        ))(ZIO.fail(_))
        applicationType = request.applicationType.getOrElse(ApplicationType.web)
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeFrontedNative(
          clientId = request.id,
          applicationType = applicationType,
          authMethod = request.authMethod,
          hasEdgeClientCertificate = request.edgeClientCertificate.isDefined,
          requirePushedAuthorizationRequests = request.requirePushedAuthorizationRequests,
          dpopBoundAccessTokens = request.dpopBoundAccessTokens,
          certificateBoundAccessTokens = request.certificateBoundAccessTokens,
          redirectUris = request.redirectUris.map(uri => uri: String),
        ))(ZIO.fail(_))
        // An edge-fronted native client reaches auth on its own mutual-TLS listener (#417),
        // which reads the certificate off the handshake -- the tenant header is never consulted.
        _ <- validateMtlsTermination(
          request.id,
          request.tenantId,
          request.mtlsAuth.filterNot(_ => isEdgeFrontedNative(applicationType, request.authMethod)),
        )
        secret <- request.authMethod match
          case AuthMethod.client_secret => presetSecret.fold(generateSecret)(ZIO.succeed(_)).asSome
          case _                        => ZIO.none
        encryptedSecret <- ZIO.foreach(secret)(encryptRawSecret)
        encryptedEdgeSigningKey <- ZIO.foreach(request.edgeSigningKey)(encryptEdgeSigningKey)
        encryptedEdgeCertificate <- ZIO.foreach(request.edgeClientCertificate)(encryptEdgeClientCertificate)
        // Truncated to microseconds: Postgres' TIMESTAMPTZ carries no more than that, and a
        // value returned here should be the one the row actually holds, not a JVM clock's
        // extra digits that storage would just drop.
        registeredAt <- Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))
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
          authMethod = request.authMethod,
          mtlsAuth = request.mtlsAuth.map(normaliseMtlsAuth),
          certificateBoundAccessTokens = request.certificateBoundAccessTokens,
          jwks = jwks,
          requireSignedRequestObject = request.requireSignedRequestObject,
          requirePushedAuthorizationRequests = request.requirePushedAuthorizationRequests,
          edgeSigningKey = encryptedEdgeSigningKey,
          edgeClientCertificate = encryptedEdgeCertificate,
          template = request.template,
          createdAt = registeredAt,
          applicationType = applicationType,
        )
        _ <- validateSecurityProfile(client).when(enforceSecurityProfile)
        _ <- clientRepository.createClient(client)
      yield RegisteredClient(secret, registeredAt, generatedKey.map(_.privateKey))

    override def updateClient(
        request: UpdateClientRequest,
        enforceSecurityProfile: Boolean = true,
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      for
        _ <- validateConsentUris(
          "logoUri" -> request.logoUri.flatMap(patchValue),
          "policyUri" -> request.policyUri.flatMap(patchValue),
          "tosUri" -> request.tosUri.flatMap(patchValue),
        )
        _ <- validateLogoutUri("frontChannelLogoutUri", request.frontChannelLogoutUri.flatMap(patchValue))
        _ <- validateLogoutUri("backChannelLogoutUri", request.backChannelLogoutUri.flatMap(patchValue))
        // Falls back to the repository on a cache miss: a client registered a moment ago may
        // not have reached the cache yet, and treating that as "no such client" would skip every
        // validation below -- exactly the miss `rejectSecretlessClient` guards against elsewhere.
        current <- cache.get.map(_.find(_.id == request.clientId)).flatMap:
          case some @ Some(_) => ZIO.succeed(some)
          case None => clientRepository.find(request.clientId).flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, clientSecretsKey)))
        // Before anything reads the patch against the stored method, so that an attempt to move
        // it is refused as that, rather than as whichever credential check it would also fail.
        _ <- ZIO.foreachDiscard(current.flatMap: client =>
          InvalidRegistrationConfiguration.validateAuthMethodUnchanged(request.clientId, request.authMethod, client.authMethod),
        )(ZIO.fail(_))
        // Only what the patch adds: a URI registered before this rule existed stays removable.
        _ <- validateRedirectUris(current.map(_.tenantId), request.redirectUris.add)
        edgeSigningKey <- ZIO.foreach(current)(effectiveEdgeSigningKey(request, _)).map(_.flatten)
        edgeCertificate = current.flatMap(effectiveEdgeClientCertificate(request, _))
        _ <- ZIO.foreachDiscard(current): client =>
          // What the client would be once the patch is applied, for the two checks that read
          // both fields at once. Everything below reads `applyTo`/`getOrElse` the same way:
          // a patch that leaves a setting alone still has to leave the client valid.
          val applicationType = request.applicationType.getOrElse(client.applicationType)
          val authMethod = client.authMethod
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
            authMethod = client.authMethod,
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
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeClientCertificate(
            clientId = request.clientId,
            edgeClientCertificate = edgeCertificate,
            mtlsAuth = request.mtlsAuth.applyTo(client.mtlsAuth).map(normaliseMtlsAuth),
            jwks = request.jwks.applyTo(client.jwks),
            requireSignedRequestObject =
              request.requireSignedRequestObject.getOrElse(client.requireSignedRequestObject),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateDpopKeyPolicy(
            clientId = request.clientId,
            dpopMinRsaKeySize = request.dpopMinRsaKeySize.applyTo(client.dpopMinRsaKeySize),
          ))(ZIO.fail(_)) *> ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateEdgeFrontedNative(
            clientId = request.clientId,
            applicationType = applicationType,
            authMethod = authMethod,
            hasEdgeClientCertificate = edgeCertificate.isDefined,
            requirePushedAuthorizationRequests =
              request.requirePushedAuthorizationRequests.getOrElse(client.requirePushedAuthorizationRequests),
            dpopBoundAccessTokens = request.dpopBoundAccessTokens.getOrElse(client.dpopBoundAccessTokens),
            certificateBoundAccessTokens =
              request.certificateBoundAccessTokens.getOrElse(client.certificateBoundAccessTokens),
            redirectUris = (client.redirectUris -- request.redirectUris.remove ++ request.redirectUris.add)
              .map(uri => uri: String),
          ))(ZIO.fail(_)) *> validateMtlsTermination(
            request.clientId,
            client.tenantId,
            request.mtlsAuth.applyTo(client.mtlsAuth)
              .filterNot(_ => isEdgeFrontedNative(applicationType, authMethod)),
          ) *> validateSecurityProfile(patchedForProfile(request, client)).when(enforceSecurityProfile)
        edgeSigningKeyPatch <- ZIO.foreach(request.edgeSigningKey):
          case Patch.Modified(key) => encryptEdgeSigningKey(key).map(Patch.Modified(_))
          case Patch.Deleted => ZIO.succeed(Patch.Deleted)
        edgeCertificatePatch <- ZIO.foreach(request.edgeClientCertificate):
          case Patch.Modified(certificate) => encryptEdgeClientCertificate(certificate).map(Patch.Modified(_))
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
            edgeClientCertificate = edgeCertificatePatch,
            applicationType = request.applicationType,
          ),
        )
      yield ()

    override def rotateClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Secret] =
      for
        _ <- rejectSecretlessClient(clientId)
        newSecret <- generateSecret
        encryptedSecret <- encryptRawSecret(newSecret)
        _ <- clientRepository.rotateClientSecret(clientId, encryptedSecret)
      yield newSecret

    override def deletePreviousClientSecret(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit] =
      rejectSecretlessClient(clientId) *> clientRepository.deletePreviousClientSecret(clientId)

    /** The client as the patch will leave it, in the settings the security profile reads --
      * the rest is left as stored, since nothing the profile checks depends on it.
      *
      * `redirectUris` is folded in the order the repository folds it (`-- remove ++ add`, see
      * `PostgresOAuthClientRepository.updateClient`), not the reverse: a URI named in both
      * sets is stored, so it has to be a URI the profile was held to. Applying the two the
      * other way round dropped it from the check while the row kept it.
      */
    private def patchedForProfile(request: UpdateClientRequest, client: OAuthClientRecord): OAuthClientRecord =
      client.copy(
        mtlsAuth = request.mtlsAuth.applyTo(client.mtlsAuth),
        certificateBoundAccessTokens = request.certificateBoundAccessTokens.getOrElse(client.certificateBoundAccessTokens),
        dpopBoundAccessTokens = request.dpopBoundAccessTokens.getOrElse(client.dpopBoundAccessTokens),
        requirePushedAuthorizationRequests =
          request.requirePushedAuthorizationRequests.getOrElse(client.requirePushedAuthorizationRequests),
        redirectUris = client.redirectUris -- request.redirectUris.remove ++ request.redirectUris.add,
        applicationType = request.applicationType.getOrElse(client.applicationType),
      )

    /** Refuses a client whose method is not `client_secret`, public or not: handing a
      * `private_key_jwt` client a freshly rotated secret would print a credential the token
      * endpoint refuses, and the operator would have no way to tell that from one it accepts.
      *
      * Reads the client from the repository rather than the cache: a client registered a
      * moment ago may not have reached the cache yet, and a stale miss would let one through.
      * An unknown client is left to the repository, which ignores it.
      */
    private def rejectSecretlessClient(clientId: ClientId): IO[ClientHasNoSecret | Throwable, Unit] =
      clientRepository.find(clientId).flatMap: client =>
        ZIO.fail(ClientHasNoSecret(clientId)).when(client.exists(!_.usesSecret)).unit

    override def deleteClient(clientId: ClientId): Task[Unit] =
      clientRepository.deleteClient(clientId)

    override def sync(event: SyncEvent.ClientsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        clientRepository.find(event.id).flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, clientSecretsKey))),
      )

    override def refreshNow: Task[Unit] =
      clientRepository.getAll
        .flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, clientSecretsKey)))
        .flatMap(cache.set(_))

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

    private def isEdgeFrontedNative(applicationType: ApplicationType, authMethod: AuthMethod): Boolean =
      applicationType == ApplicationType.native && authMethod == AuthMethod.tls_client_auth

    /** Reads the tenant's challenge settings only when there is an `mtlsAuth` to justify it:
      * every other registration would pay for a lookup whose answer it has no use for.
      *
      * Read through the repository ([[ChallengeSettingsService.getMtlsCertificateHeader]]),
      * not the cache: bootstrap sets this header and registers `central-admin` against it in
      * the same process (`BootstrapService.seedMtlsTermination` then `seedClient`), and the
      * cache's refresh interval does not run between the two. The cached [[getSettings]]
      * would see no header yet and refuse the very registration that just set it.
      */
    private def validateMtlsTermination(
        clientId: ClientId,
        tenantId: TenantId,
        mtlsAuth: Option[MutualTlsAuth],
    ): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      ZIO.when(mtlsAuth.nonEmpty):
        challengeSettingsService.getMtlsCertificateHeader(tenantId).flatMap: mtlsCertificateHeader =>
          ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateMtlsTermination(
            clientId,
            mtlsAuth,
            mtlsCertificateHeader,
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

    /** FAPI 2.0 Security Profile §5.3.2.2-8: a redirect URI is registered only as `https://`, or
      * `http://` to a loopback address (RFC 8252 §7.3). The request body's decoder has already
      * applied the structural check ([[RedirectUri.parse]]); this is the registration policy on
      * top of it. See [[RedirectUri.validateForRegistration]].
      *
      * A tenant on the `standard` security profile may additionally register a reverse-domain
      * private-use scheme (RFC 8252 §7.1) -- plain OAuth permits one for native apps, FAPI 2.0
      * does not. The profile is read only when a URI actually needs that allowance, like
      * [[validateMtlsTermination]] reads its settings; a tenant with no settings, or a patch to
      * a client that cannot be found, is held to the default profile (`fapi2`).
      *
      * Read through the repository, as [[validateSecurityProfile]] reads it and for its reason:
      * a registration that follows a profile switch has to be held to the profile the switch
      * just stored, which the cache may not carry yet.
      */
    private def validateRedirectUris(
        tenantId: Option[TenantId],
        uris: Set[RedirectUri],
    ): IO[InvalidConsentUri | Throwable, Unit] =
      val strictlyInvalid = uris.filter(RedirectUri.validateForRegistration(_).isLeft)
      ZIO.unless(strictlyInvalid.isEmpty):
        for
          profile <- ZIO.foreach(tenantId)(challengeSettingsService.getSecurityProfile)
            .map(_.getOrElse(ChallengeSettingsRecord.DefaultSecurityProfile))
          allowPrivateUseSchemes = profile == SecurityProfile.standard
          _ <- ZIO.foreachDiscard(strictlyInvalid): uri =>
            ZIO.fromEither(RedirectUri.validateForRegistration(uri, allowPrivateUseSchemes))
              .mapError(reason => InvalidConsentUri("redirectUris", s"'$uri': $reason"))
        yield ()
      .unit

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

    /** The certificate as it is stored: the PEM text that was registered, under the same
      * at-rest key. Kept verbatim for the same reason the signing key keeps its document —
      * what an edge presents has to be the bytes the client registered, since RFC 8705 §3.1
      * binds tokens to their hash. */
    private def encryptEdgeClientCertificate(certificate: PrivateClientCertificate): Task[Secret] =
      encryptRawSecret(Secret(certificate.pem.getBytes(StandardCharsets.UTF_8)))

    /** The certificate the client will hold once this patch is applied, on the same terms as
      * [[effectiveEdgeSigningKey]]: a patch that leaves it alone can still invalidate it, by
      * clearing the `mtlsAuth` that decides what it is compared against or the `jwks` a
      * self-signed registration matches it by.
      *
      * Needs no parsing to read back, PEM being how it was stored, so a stored certificate is
      * always recovered — whether it still validates is what the caller is about to ask.
      */
    private def effectiveEdgeClientCertificate(
        request: UpdateClientRequest,
        client: OAuthClientRecord,
    ): Option[PrivateClientCertificate] =
      request.edgeClientCertificate match
        case Some(Patch.Modified(certificate)) => Some(certificate)
        case Some(Patch.Deleted) => None
        case None =>
          client.edgeClientCertificate.map(stored =>
            PrivateClientCertificate(String(stored, StandardCharsets.UTF_8)),
          )

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
