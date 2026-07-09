package versola.oauth.jwks

import versola.oauth.client.CentralSyncTokenService
import versola.util.{CacheSource, CoreConfig, JWT}
import zio.http.Request
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

/** Pulls the JWKS from central (`/configuration/jwks/sync`). Central is the
  * single source of truth; auth caches the keys for signing and verification.
  */
trait JwksSyncClient extends CacheSource[JWT.PublicKeys]

object JwksSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, JwksSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends JwksSyncClient:
    private val JwksURL = config.central.url / "configuration" / "jwks" / "sync"

    override def getAll: Task[JWT.PublicKeys] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(JwksURL))
          .flatMap(_.body.asJsonFromCodec[JWT.PublicKeys])
