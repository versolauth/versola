package versola.oauth.client

import versola.oauth.client.model.OtpTemplateRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait OtpTemplateSyncClient extends CacheSource[Vector[OtpTemplateRecord]]:
  def getAll: Task[Vector[OtpTemplateRecord]]

object OtpTemplateSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, OtpTemplateSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OtpTemplateSyncClient:
    private val TemplatesURL = config.central.url / "configuration" / "challenges" / "otp-templates" / "sync"

    override def getAll: Task[Vector[OtpTemplateRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(TemplatesURL)).flatMap(_.bodyAs[TemplatesResponse])
      .map(_.templates)

  case class TemplatesResponse(templates: Vector[OtpTemplateRecord]) derives JsonCodec
