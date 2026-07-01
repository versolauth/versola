package versola.edge

import versola.util.{CacheSource, JWT}
import zio.http.{Client, Header, Request}
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

trait JwksSyncClient extends CacheSource[JWT.PublicKeys]

object JwksSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, JwksSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends JwksSyncClient:
    private val JwksURL = config.central.url / "configuration" / "jwks" / "sync"

    override def getAll: Task[JWT.PublicKeys] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(JwksURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        keys <- response.body.asJsonFromCodec[JWT.PublicKeys]
      yield keys
