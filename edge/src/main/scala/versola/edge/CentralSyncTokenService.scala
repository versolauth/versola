package versola.edge

import versola.util.{CacheSource, JWT, ReloadingCache}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CentralSyncTokenService:
  def getToken: UIO[String]

object CentralSyncTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & EdgeConfig, Throwable, CentralSyncTokenService] = {
    TokenSource >>>
      ZLayer(ReloadingCache.make[String](Schedule.spaced(ReloadInterval))) >>>
      ZLayer.fromFunction(Impl(_, _))
  }

  private val TokenSource: ZLayer[EdgeConfig, Throwable, CacheSource[String]] = ZLayer:
    ZIO.serviceWith[EdgeConfig] { config =>
      new CacheSource[String]:
        override def getAll: Task[String] =
          JWT.serialize(
            claims = JWT.Claims(
              issuer = "edge",
              subject = "edge",
              audience = List("central"),
              custom = Json.Obj(),
            ),
            ttl = TokenTtl,
            signature = JWT.Signature.Asymmetric(
              algorithm = JWT.Algorithm.RS256,
              keyId = config.keyId,
              privateKey = config.privateKey,
            ),
            headers = Map("edge_id" -> config.id),
          )
    }

  class Impl(
      cache: ReloadingCache[String],
      config: EdgeConfig,
  ) extends CentralSyncTokenService:
    override def getToken: UIO[String] = cache.get
