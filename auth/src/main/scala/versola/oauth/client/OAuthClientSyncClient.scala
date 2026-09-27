package versola.oauth.client

import versola.oauth.client.model.{AuthFlow, AuthMethod, ClientId, ConsentFlow, MutualTlsAuth, OAuthClientRecord, RegistrationFlow, ScopeToken, TenantId}
import versola.util.{Base64, CacheSource, CoreConfig, Dpop, JsonWebKeySet, Secret, SecurityService}
import zio.http.{Request, URL}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, URLayer, ZIO, ZLayer, durationInt}

trait OAuthClientSyncClient extends CacheSource[Map[ClientId, OAuthClientRecord]]:
  def getAll: Task[Map[ClientId, OAuthClientRecord]]

object OAuthClientSyncClient:
  val live: URLayer[CoreConfig & SecurityService & CentralSyncTokenService, OAuthClientSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthClientSyncClient:
    private val ClientsURL = config.central.url / "configuration" / "clients" / "sync"

    override def getAll: Task[Map[ClientId, OAuthClientRecord]] =
      for
        response <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(ClientsURL)).flatMap(_.bodyAs[OAuthClientsSyncResponse])
        decryptedClients <- ZIO.foreach(response.clients) { client =>
          for
            secret <- ZIO.foreach(client.secret)(decryptSecret)
            previousSecret <- ZIO.foreach(client.previousSecret)(decryptSecret)
          yield OAuthClientRecord(
            id = client.id,
            tenantId = client.tenantId,
            clientName = client.clientName,
            redirectUris = client.redirectUris,
            scope = client.scope,
            secret = secret,
            previousSecret = previousSecret,
            accessTokenTtl = client.accessTokenTtl,
            refreshTokenTtl = client.refreshTokenTtl,
            theme = client.theme,
            authFlow = client.authFlow,
            registrationFlow = client.registrationFlow,
            otpTemplateId = client.otpTemplateId,
            frontChannelLogoutUri = client.frontChannelLogoutUri.flatMap(URL.decode(_).toOption),
            frontChannelLogoutSessionRequired = client.frontChannelLogoutSessionRequired,
            backChannelLogoutUri = client.backChannelLogoutUri.flatMap(URL.decode(_).toOption),
            logoUri = client.logoUri,
            policyUri = client.policyUri,
            tosUri = client.tosUri,
            consentFlow = client.consentFlow,
            dpopBoundAccessTokens = client.dpopBoundAccessTokens,
            dpopSigningAlgs = client.dpopSigningAlgs,
            dpopMinRsaKeySize = client.dpopMinRsaKeySize,
            authMethod = client.authMethod,
            mtlsAuth = client.mtlsAuth,
            certificateBoundAccessTokens = client.certificateBoundAccessTokens,
            jwks = client.jwks,
            requireSignedRequestObject = client.requireSignedRequestObject,
            requirePushedAuthorizationRequests = client.requirePushedAuthorizationRequests,
          )
        }
      yield decryptedClients.map(it => it.id -> it).toMap

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptAes256(encrypted, config.central.secretKey)
      yield Secret(decrypted)

    private case class OAuthClientRecordWithEncryptedSecrets(
        id: ClientId,
        tenantId: TenantId,
        clientName: Map[String, String],
        redirectUris: Set[String],
        scope: Set[ScopeToken],
        secret: Option[String],
        previousSecret: Option[String],
        accessTokenTtl: Duration,
        refreshTokenTtl: Duration,
        theme: String,
        authFlow: Option[AuthFlow],
        registrationFlow: Option[RegistrationFlow],
        otpTemplateId: String,
        frontChannelLogoutUri: Option[String],
        frontChannelLogoutSessionRequired: Boolean,
        backChannelLogoutUri: Option[String],
        logoUri: Option[String],
        policyUri: Option[String],
        tosUri: Option[String],
        consentFlow: Option[ConsentFlow],
        dpopBoundAccessTokens: Boolean,
        /** Defaulted for the same reason as the flags below: an auth node may be reading a
          * central that predates the field. */
        dpopSigningAlgs: Set[Dpop.Algorithm] = Set.empty,
        dpopMinRsaKeySize: Option[Int] = None,
        authMethod: AuthMethod,
        mtlsAuth: Option[MutualTlsAuth],
        certificateBoundAccessTokens: Boolean,
        jwks: Option[JsonWebKeySet],
        /** Defaulted so that an auth node reading a central that predates the field keeps the
          * behaviour it already had, rather than failing to decode the sync response. */
        requireSignedRequestObject: Boolean = false,
        requirePushedAuthorizationRequests: Boolean = false,
    ) derives JsonCodec

    private case class OAuthClientsSyncResponse(
        clients: Vector[OAuthClientRecordWithEncryptedSecrets],
    ) derives JsonCodec
