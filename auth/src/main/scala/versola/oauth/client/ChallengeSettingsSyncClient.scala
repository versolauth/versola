package versola.oauth.client

import versola.oauth.client.model.ChallengeSettingsRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ChallengeSettingsSyncClient extends CacheSource[Vector[ChallengeSettingsRecord]]:
  def getAll: Task[Vector[ChallengeSettingsRecord]]

object ChallengeSettingsSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, ChallengeSettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ChallengeSettingsSyncClient:
    private val ChallengeSettingsURL = config.central.url / "configuration" / "challenges" / "challenge-settings" / "sync"

    override def getAll: Task[Vector[ChallengeSettingsRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(ChallengeSettingsURL)).flatMap(_.bodyAs[ChallengeSettingsResponse])
      .map(_.settings)

  case class ChallengeSettingsResponse(settings: Vector[ChallengeSettingsRecord]) derives JsonCodec
