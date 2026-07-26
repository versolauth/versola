package versola.central.users

import versola.central.CentralConfig
import versola.util.{CacheSource, JWT, ReloadingCache}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait AuthTokenService:
  def getToken: UIO[String]
  def refreshToken(): Task[String]

object AuthTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & CentralConfig, Throwable, AuthTokenService] =
    val cacheLayer = TokenSource >>> ZLayer(ReloadingCache.make[String](Schedule.spaced(ReloadInterval)))
    (cacheLayer ++ ZLayer.service[CentralConfig]) >>> ZLayer.fromFunction(Impl(_, _))

  private def generateToken(config: CentralConfig): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = "central",
        subject = "central",
        audience = List("auth"),
        custom = Json.Obj(),
      ),
      ttl = TokenTtl,
      signature = JWT.Signature.Symmetric(config.secretKey),
    )

  private val TokenSource: ZLayer[CentralConfig, Throwable, CacheSource[String]] = ZLayer:
    ZIO.serviceWith[CentralConfig]: config =>
      new CacheSource[String]:
        override def getAll: Task[String] = generateToken(config)

  class Impl(cache: ReloadingCache[String], config: CentralConfig) extends AuthTokenService:
    override def getToken: UIO[String] = cache.get

    override def refreshToken(): Task[String] =
      generateToken(config).tap(cache.set(_))
