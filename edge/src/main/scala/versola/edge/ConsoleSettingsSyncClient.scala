package versola.edge

import versola.util.CacheSource
import zio.http.{Client, Header, Request, Status}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** What the admin console reads through this edge that is not about its caller, as central holds it in the system settings: the time zone
  * to show times in for an administrator whose profile has no `zoneinfo` of their own. `None`: none is set, and the console uses the
  * browser's zone.
  *
  * The same for every edge, unlike the policies, which are each edge's own row; central still serves it to a registered edge only.
  */
case class EdgeConsoleSettings(uiTimeZone: Option[String])

trait ConsoleSettingsSyncClient extends CacheSource[EdgeConsoleSettings]

object ConsoleSettingsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, ConsoleSettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ConsoleSettingsSyncClient:
    private val ConsoleSettingsURL = config.central.url / "edge" / "sync" / "console"

    override def getAll: Task[EdgeConsoleSettings] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ConsoleSettingsURL).addHeader(Header.Authorization.Bearer(token))
        settings <- ZIO.scoped:
          centralSyncTokenService.syncRequest(httpClient, request).flatMap: response =>
            // A central that predates the endpoint has no default to give: that is "none set", not a reason for the edge not to start.
            if response.status == Status.NotFound then ZIO.succeed(None)
            // not decoded: an error body with no `uiTimeZone` in it would read as "none set"
            else if !response.status.isSuccess then
              ZIO.fail(RuntimeException(s"central answered ${response.status.code} to the console settings sync"))
            else response.bodyAs[EdgeConsoleSyncResponse].map(_.uiTimeZone)
      yield EdgeConsoleSettings(settings)

  private case class EdgeConsoleSyncResponse(
      uiTimeZone: Option[String],
  ) derives JsonCodec
