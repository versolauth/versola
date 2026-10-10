package versola.oauth.client

import versola.util.{CacheSource, ConfigSnapshot, CoreConfig, JWT, ReloadingCache}
import zio.http.{Client, Header, Method, Request, Response, Status}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CentralSyncTokenService:
  def getToken: UIO[String]

  /** Sends `request` to central with a sync token. A `GET` is a configuration sync and goes
    * through the [[ConfigSnapshot]].
    */
  def syncRequest(request: Request): ZIO[Scope, Throwable, Response]

object CentralSyncTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & CoreConfig & Client & ConfigSnapshot, Throwable, CentralSyncTokenService] =
    TokenSource >>>
      ZLayer(ReloadingCache.make[String](ReloadInterval)) >>>
      ZLayer.fromFunction(Impl(_, _, _, _))

  private def generateToken(config: CoreConfig): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = "auth",
        subject = "auth",
        audience = List("central"),
        custom = Json.Obj(),
      ),
      ttl = TokenTtl,
      signature = JWT.Signature.Symmetric(config.central.secretKey),
    )

  private val TokenSource: ZLayer[CoreConfig, Throwable, CacheSource[String]] = ZLayer:
    ZIO.serviceWith[CoreConfig] { config =>
      new CacheSource[String]:
        override def getAll: Task[String] = generateToken(config)
    }

  class Impl(
      cache: ReloadingCache[String],
      config: CoreConfig,
      httpClient: Client,
      snapshot: ConfigSnapshot,
  ) extends CentralSyncTokenService:
    override def getToken: UIO[String] = cache.get

    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] =
      if request.method == Method.GET then snapshot.through(request)(send(request)) else send(request)

    private def send(request: Request): ZIO[Scope, Throwable, Response] =
      for
        token <- cache.get
        response <- httpClient.request(authorized(request, token))
        result <-
          if response.status == Status.Unauthorized then refreshAndRetry(request)
          else ZIO.succeed(response)
      yield result

    private def refreshAndRetry(request: Request): ZIO[Scope, Throwable, Response] =
      for
        token <- generateToken(config)
        _ <- cache.set(token)
        response <- httpClient.request(authorized(request, token))
      yield response

    private def authorized(request: Request, token: String): Request =
      request.addHeader(Header.Authorization.Bearer(token))

