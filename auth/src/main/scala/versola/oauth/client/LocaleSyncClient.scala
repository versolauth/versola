package versola.oauth.client

import versola.oauth.client.model.Locales
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait LocaleSyncClient extends CacheSource[Locales]:
  def getAll: Task[Locales]

object LocaleSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, LocaleSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends LocaleSyncClient:
    private val LocalesURL = config.central.url / "configuration" / "locales" / "sync"

    override def getAll: Task[Locales] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(LocalesURL)).flatMap(_.bodyAs[Locales])
