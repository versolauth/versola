package versola.oauth.client

import versola.oauth.client.model.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  ClientId,
  ConsentFlow,
  MutualTlsAuth,
  OAuthClientRecord,
  ScopeToken,
  TenantId,
}
import versola.util.theme.ThemeOverrides
import versola.util.{Base64, CacheSource, CoreConfig, Dpop, JsonWebKeySet, Secret, SecurityService}
import zio.http.{Request, URL}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, UIO, URLayer, ZIO, ZLayer, durationInt}

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
        clients <- ZIO.foreach(response.clients)(decodeClient)
        decryptedClients <- ZIO.foreach(clients.flatten) { client =>
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
            applicationType = client.applicationType,
            themeOverrides = client.themeOverrides,
          )
        }
      yield decryptedClients.map(it => it.id -> it).toMap

    /** One record that does not decode (an unknown value of a strictly validated field, a stored format an older
      * central wrote) costs that client, not every client: the rest still reach the cache. Only the id is logged,
      * since the rest of the record can hold secrets.
      */
    private def decodeClient(json: Json): UIO[Option[OAuthClientRecordWithEncryptedSecrets]] =
      json.as[OAuthClientRecordWithEncryptedSecrets] match
        case Right(client) => ZIO.some(client)
        case Left(_) =>
          val id = json.asObject.flatMap(_.get("id")).flatMap(_.asString).getOrElse("<no id>")
          ZIO.logWarning(s"Skipped a client that does not decode in the sync response: '$id'").as(None)

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
        /** Defaulted on the same terms: a central that predates the field only has `web`
          * clients. */
        applicationType: ApplicationType = ApplicationType.web,
        themeOverrides: Option[ThemeOverrides],
    ) derives JsonCodec

    private case class OAuthClientsSyncResponse(
        clients: Vector[Json],
    ) derives JsonCodec
