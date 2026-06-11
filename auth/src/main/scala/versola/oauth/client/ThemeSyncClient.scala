package versola.oauth.client

import versola.oauth.client.model.ThemeRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ThemeSyncClient extends CacheSource[Vector[ThemeRecord]]:
  def getAll: Task[Vector[ThemeRecord]]

object ThemeSyncClient:
  val live: URLayer[Client & CoreConfig & CentralSyncTokenService, ThemeSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ThemeSyncClient:
    private val ThemesURL = config.central.url / "configuration" / "themes" / "sync"

    override def getAll: Task[Vector[ThemeRecord]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ThemesURL).addHeader(Header.Authorization.Bearer(token))
        themes <- ZIO.scoped:
          httpClient.request(request).flatMap(_.bodyAs[ThemesResponse])
      yield themes.themes

  case class ThemesResponse(themes: Vector[ThemeRecord]) derives JsonCodec
