package versola.oauth.client

import versola.oauth.client.model.{ClientId, ClientsWithPepper, OAuthClientRecord, ScopeToken, TenantId}
import versola.util.{Base64, CacheSource, CoreConfig, Secret, SecurityService}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.prelude.NonEmptySet
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, URLayer, ZIO, ZLayer, durationInt}

trait OAuthClientSyncClient extends CacheSource[ClientsWithPepper]:
  def getAll: Task[ClientsWithPepper]

object OAuthClientSyncClient:
  val live: URLayer[Client & CoreConfig & SecurityService & CentralSyncTokenService, OAuthClientSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      httpClient: Client,
      config: CoreConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthClientSyncClient:
    private val ClientsURL = config.central.url / "configuration" / "clients" / "sync"

    override def getAll: Task[ClientsWithPepper] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ClientsURL).addHeader(Header.Authorization.Bearer(token))
        clients <- ZIO.scoped:
          httpClient.request(request).flatMap(_.bodyAs[OAuthClientsWithPepperEncrypted])
        decryptedPepper <- decryptSecret(clients.pepper)
        decryptedClients <- ZIO.foreach(clients.clients) { client =>
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
          )
        }
      yield ClientsWithPepper(decryptedClients.map(it => it.id -> it).toMap, decryptedPepper)

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
    ) derives JsonCodec

    private case class OAuthClientsWithPepperEncrypted(
        clients: Vector[OAuthClientRecordWithEncryptedSecrets],
        pepper: String,
    ) derives JsonCodec
