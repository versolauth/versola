package versola.oauth.client

import versola.oauth.client.model.FormRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait FormSyncClient extends CacheSource[Vector[FormRecord]]:
  def getAll: Task[Vector[FormRecord]]

object FormSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, FormSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends FormSyncClient:
    private val FormsURL = config.central.url / "configuration" / "forms" / "sync"

    override def getAll: Task[Vector[FormRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(FormsURL)).flatMap(_.bodyAs[FormsResponse])
      .map(_.forms)

  case class FormsResponse(forms: Vector[FormRecord]) derives JsonCodec
