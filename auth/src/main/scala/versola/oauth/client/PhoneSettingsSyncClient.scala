package versola.oauth.client

import versola.oauth.client.model.PhoneSettingsRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait PhoneSettingsSyncClient extends CacheSource[Vector[PhoneSettingsRecord]]:
  def getAll: Task[Vector[PhoneSettingsRecord]]

object PhoneSettingsSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, PhoneSettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends PhoneSettingsSyncClient:
    private val PhoneSettingsURL = config.central.url / "configuration" / "challenges" / "phone-settings" / "sync"

    override def getAll: Task[Vector[PhoneSettingsRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(PhoneSettingsURL)).flatMap(_.bodyAs[PhoneSettingsResponse])
      .map(_.settings)

  case class PhoneSettingsResponse(settings: Vector[PhoneSettingsRecord]) derives JsonCodec
