package versola.edge

import versola.edge.model.{ClientId, OAuthClient, PermissionId}
import versola.util.{Base64, CacheSource, Secret, SecurityService}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait OAuthClientsSyncClient extends CacheSource[Map[ClientId, OAuthClient]]:
  def getAll: Task[Map[ClientId, OAuthClient]]

object OAuthClientsSyncClient:
  val live: URLayer[Client & EdgeConfig & SecurityService & CentralSyncTokenService, OAuthClientsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthClientsSyncClient:
    private val ClientsURL = config.central.url / "configuration" / "clients" / "sync"

    override def getAll: Task[Map[ClientId, OAuthClient]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ClientsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetOAuthClientsSyncResponse]
        clients <- ZIO.foreach(response.clients) { client =>
          ZIO.foreach(client.secret)(decryptSecret).map(_.map(OAuthClient(client.id, _, client.permissions.map(PermissionId(_)))))
        }
      yield clients.flatten.map(x => x.id -> x).toMap

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptRsa(encrypted, config.privateKey)
      yield Secret(decrypted)

    private case class SyncOAuthClientRecord(
        id: ClientId,
        secret: Option[String],
        permissions: Set[String] = Set.empty,
    ) derives JsonCodec

    private case class GetOAuthClientsSyncResponse(
        clients: Vector[SyncOAuthClientRecord],
    ) derives JsonCodec
