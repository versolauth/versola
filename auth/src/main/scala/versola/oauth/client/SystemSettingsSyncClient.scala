package versola.oauth.client

import versola.oauth.client.model.SystemSettingsRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait SystemSettingsSyncClient extends CacheSource[SystemSettingsRecord]:
  def getAll: Task[SystemSettingsRecord]

object SystemSettingsSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, SystemSettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends SystemSettingsSyncClient:
    private val SystemSettingsURL = config.central.url / "configuration" / "system-settings" / "sync"

    override def getAll: Task[SystemSettingsRecord] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(SystemSettingsURL)).flatMap(_.bodyAs[SystemSettingsRecord])
