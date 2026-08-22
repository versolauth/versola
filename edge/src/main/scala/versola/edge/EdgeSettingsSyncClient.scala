package versola.edge

import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** The settings central holds about this edge.
  *
  * Not a `CacheSource`/`ReloadingCache` like the other sync clients: nothing reads these
  * values on request. They are applied when they change, which is a push into the service
  * that owns them rather than a lookup.
  *
  * @param revocationCacheSize how many revocations this edge keeps in memory.
  */
case class EdgeSettings(revocationCacheSize: Int) derives JsonCodec

trait EdgeSettingsSyncClient:
  def get: Task[EdgeSettings]

object EdgeSettingsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, EdgeSettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends EdgeSettingsSyncClient:
    // "self": the edge is identified by the token it authenticates with, so it cannot ask
    // for another edge's settings.
    private val SettingsURL = config.central.url / "configuration" / "edges" / "self" / "sync"

    override def get: Task[EdgeSettings] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(SettingsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        settings <- response.bodyAs[EdgeSettings]
      yield settings
