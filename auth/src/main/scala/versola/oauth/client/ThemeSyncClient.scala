package versola.oauth.client

import versola.oauth.client.model.ThemeRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, UIO, URLayer, ZIO, ZLayer}

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
        centralSyncTokenService.syncRequest(Request.get(ThemesURL)).flatMap(_.bodyAs[RawThemesResponse])
      .flatMap(response => ZIO.foreach(response.themes)(decode)).map(_.flatten)

    // One theme that cannot be read (an unknown value, a malformed colour) must not drop the others: it is skipped, and
    // only its id is logged, never its content.
    private def decode(raw: Json): UIO[Option[ThemeRecord]] =
      raw.as[ThemeRecord] match
        case Right(record) => ZIO.succeed(Some(record))
        case Left(_) =>
          val id = raw.asObject.flatMap(_.get("id")).flatMap(_.asString).getOrElse("<unknown>")
          ZIO.logWarning(s"Skipped a theme central sent that could not be read: id '$id'").as(None)

  // What goes over the wire: the records are decoded one by one, see `decode`.
  case class RawThemesResponse(themes: Vector[Json]) derives JsonCodec

  case class ThemesResponse(themes: Vector[ThemeRecord]) derives JsonCodec
