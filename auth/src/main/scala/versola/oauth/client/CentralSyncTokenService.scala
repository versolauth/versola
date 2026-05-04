package versola.oauth.client

import versola.util.{CacheSource, CoreConfig, JWT, ReloadingCache}
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CentralSyncTokenService:
  def getToken: UIO[String]

object CentralSyncTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & CoreConfig, Throwable, CentralSyncTokenService] =
    TokenSource >>>
      ZLayer(ReloadingCache.make[String](Schedule.spaced(ReloadInterval))) >>>
      ZLayer.fromFunction(Impl(_, _))

  private val TokenSource: ZLayer[CoreConfig, Throwable, CacheSource[String]] = ZLayer:
    ZIO.serviceWith[CoreConfig] { config =>
      new CacheSource[String]:
        override def getAll: Task[String] =
          JWT.serialize(
            claims = JWT.Claims(
              issuer = "auth",
              subject = "auth",
              audience = List("central"),
              custom = zio.json.ast.Json.Obj(),
            ),
            ttl = TokenTtl,
            signature = JWT.Signature.Symmetric(config.central.secretKey),
          )
    }

  class Impl(
      cache: ReloadingCache[String],
      config: CoreConfig,
  ) extends CentralSyncTokenService:
    override def getToken: UIO[String] = cache.get

