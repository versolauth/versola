package versola.oauth.client

import versola.oauth.client.model.{ClaimRecord, ScopeRecord}
import versola.util.{CacheSource, CoreConfig}
import zio.http.{Client, Header, Request}
import zio.json.{JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Chunk, Task, URLayer, ZIO, ZLayer}

trait OAuthScopeSyncClient extends CacheSource[Vector[ScopeRecord]]:
  def getAll: Task[Vector[ScopeRecord]]

object OAuthScopeSyncClient:
  val live: URLayer[Client & CoreConfig & CentralSyncTokenService, OAuthScopeSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthScopeSyncClient:
    private val ScopesURL = config.central.url / "v1" / "configuration" / "scopes" / "sync"

    override def getAll: Task[Vector[ScopeRecord]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ScopesURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        scopes <- response.bodyAs[ScopeResponse]
      yield scopes.scopes

  case class ScopeResponse(scopes: Vector[ScopeRecord]) derives JsonCodec