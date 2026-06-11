package versola.edge

import versola.edge.model.{AuthorizationPreset, PresetId}
import versola.util.{CacheSource, RedirectUri}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait AuthorizationPresetsSyncClient extends CacheSource[Map[PresetId, AuthorizationPreset]]:
  def getAll: Task[Map[PresetId, AuthorizationPreset]]

object AuthorizationPresetsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, AuthorizationPresetsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends AuthorizationPresetsSyncClient:
    private val PresetsURL = config.central.url / "configuration" / "auth-request-presets" / "sync"

    override def getAll: Task[Map[PresetId, AuthorizationPreset]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(PresetsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetAuthorizationPresetsSyncResponse]
      yield response.presets.map(x => x.id -> x).toMap

    private case class GetAuthorizationPresetsSyncResponse(
        presets: Vector[AuthorizationPreset],
    ) derives JsonCodec
