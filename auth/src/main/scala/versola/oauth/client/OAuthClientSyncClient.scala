package versola.oauth.client

import versola.oauth.client.model.{AuthFlow, ClientId, OAuthClientRecord, ScopeToken, TenantId}
import versola.util.{Base64, CacheSource, CoreConfig, Secret, SecurityService}
import zio.http.Request
import zio.json.JsonCodec
import zio.prelude.NonEmptySet
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
            externalAudience = client.externalAudience,
            secret = secret,
            previousSecret = previousSecret,
            accessTokenTtl = client.accessTokenTtl,
            refreshTokenTtl = client.refreshTokenTtl,
            theme = client.theme,
            authFlow = client.authFlow,
            otpTemplateId = client.otpTemplateId,
          )
        }
      yield decryptedClients.map(it => it.id -> it).toMap

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptAes256(encrypted, config.central.secretKey)
      yield Secret(decrypted)

    private given JsonCodec[NonEmptySet[String]] =
      JsonCodec.nonEmptyChunk[String].transform(NonEmptySet.fromNonEmptyChunk, _.toNonEmptyChunk)

    private case class OAuthClientRecordWithEncryptedSecrets(
        id: ClientId,
        tenantId: TenantId,
        clientName: String,
        redirectUris: NonEmptySet[String],
        scope: Set[ScopeToken],
        externalAudience: List[ClientId],
        secret: Option[String],
        previousSecret: Option[String],
        accessTokenTtl: Duration,
        refreshTokenTtl: Duration,
        theme: String,
        authFlow: Option[AuthFlow],
        otpTemplateId: String,
    ) derives JsonCodec

    private case class OAuthClientsSyncResponse(
        clients: Vector[OAuthClientRecordWithEncryptedSecrets],
    ) derives JsonCodec
