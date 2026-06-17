package versola.oauth.client

import versola.oauth.client.model.ThemeRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ThemeSyncClient extends CacheSource[Vector[ThemeRecord]]:
  def getAll: Task[Vector[ThemeRecord]]

object ThemeSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, ThemeSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ThemeSyncClient:
    private val ThemesURL = config.central.url / "configuration" / "themes" / "sync"

    override def getAll: Task[Vector[ThemeRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(ThemesURL)).flatMap(_.bodyAs[ThemesResponse])
      .map(_.themes)

  case class ThemesResponse(themes: Vector[ThemeRecord]) derives JsonCodec
