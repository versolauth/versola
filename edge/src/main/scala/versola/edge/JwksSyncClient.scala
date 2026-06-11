package versola.edge

import versola.util.{CacheSource, JWT}
import zio.http.{Client, Header, Request}
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, ZIO, ZLayer}

trait JwksSyncClient extends CacheSource[JWT.PublicKeys]

object JwksSyncClient:
  val live: ZLayer[Client & EdgeConfig, Nothing, JwksSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
  ) extends JwksSyncClient:
    private val JwksURL = config.versolaUrl / ".well-known" / "jwks.json"

    override def getAll: Task[JWT.PublicKeys] =
      for
        request = Request.get(JwksURL)
        response <- ZIO.scoped(httpClient.request(request))
        keys <- response.body.asJsonFromCodec[JWT.PublicKeys]
      yield keys
