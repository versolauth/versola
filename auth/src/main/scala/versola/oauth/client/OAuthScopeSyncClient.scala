package versola.oauth.client

import versola.oauth.client.model.{ClaimRecord, ScopeRecord}
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.{JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Chunk, Task, URLayer, ZIO, ZLayer}

trait OAuthScopeSyncClient extends CacheSource[Vector[ScopeRecord]]:
  def getAll: Task[Vector[ScopeRecord]]

object OAuthScopeSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, OAuthScopeSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthScopeSyncClient:
    private val ScopesURL = config.central.url / "configuration" / "scopes" / "sync"

    override def getAll: Task[Vector[ScopeRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(ScopesURL)).flatMap(_.bodyAs[ScopeResponse])
      .map(_.scopes)

  case class ScopeResponse(scopes: Vector[ScopeRecord]) derives JsonCodec