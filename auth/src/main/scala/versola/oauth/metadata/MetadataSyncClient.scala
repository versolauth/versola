package versola.oauth.metadata

import versola.oauth.client.CentralSyncTokenService
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

trait MetadataSyncClient extends CacheSource[Option[Json.Obj]]

object MetadataSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, MetadataSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends MetadataSyncClient:
    private val MetadataSyncURL = config.central.url / "configuration" / "server-metadata" / "sync"

    override def getAll: Task[Option[Json.Obj]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(MetadataSyncURL))
          .flatMap(_.body.asJsonFromCodec[Json.Obj])
          .map(Some(_))
