package versola.oauth.client

import versola.util.{CacheSource, ConfigSnapshot, CoreConfig, JWT, ReloadingCache}
import zio.http.{Client, Header, Method, Request, Response, Status}
import zio.json.ast.Json
import zio.{IO, Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CentralSyncTokenService:
  def getToken: UIO[String]

  /** Sends `request` to central with a sync token. A `GET` is a configuration sync: it goes
    * through the [[ConfigSnapshot]], and an answer that is not a success fails with
    * [[CentralSyncTokenService.UnexpectedStatus]] instead of being handed to the caller, which would
    * decode its body as the configuration it asked for. Any other method answers with whatever
    * central said, for a caller whose protocol gives the statuses a meaning (EST).
    */
  def syncRequest(request: Request): ZIO[Scope, Throwable, Response]

  /** Sends `request` to central with a sync token, whatever its method, and never through the [[ConfigSnapshot]]: for a
    * `GET` whose answer depends on what the caller already has (a delta since a version), so that it is not recorded and
    * not replayed in place of an answer to another question. */
  def liveRequest(request: Request): ZIO[Scope, Throwable, Response]

object CentralSyncTokenService:

  /** Central, or something in front of it, answered a configuration sync with a status that is not
    * a success. Says the status and the path and nothing of the body: a gateway's error page has
    * no place in a log line, and the query of a sync can name what was asked for.
    */
  final case class UnexpectedStatus(status: Status, path: String)
    extends RuntimeException(s"central answered the sync of $path with ${status.code}")

  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  /** The JOSE header that names the cell a token comes from (versolauth/versola#1050). */
  val CellIdHeader = "cell_id"

  def live: ZLayer[Scope & CoreConfig & Client & ConfigSnapshot, Throwable, CentralSyncTokenService] =
    TokenSource >>>
      ZLayer(ReloadingCache.make[String](ReloadInterval)) >>>
      ZLayer.fromFunction(Impl(_, _, _, _))

  private def generateToken(config: CoreConfig): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = "auth",
        subject = config.cellId.getOrElse("auth"),
        audience = List("central"),
        custom = Json.Obj(),
      ),
      ttl = TokenTtl,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, config.central.keyId, config.central.privateKey),
      // Informative only: central decides who calls from the key. It refuses a header that names another cell than the one
      // the key belongs to.
      headers = config.cellId.map(id => CellIdHeader -> (id: String)).toMap,
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
      if request.method == Method.GET then snapshot.through(request)(send(request)).flatMap(succeeded(request))
      else send(request)

    // After the snapshot, which has already turned a 5xx into `SourceFailed` (the first load can then
    // start from the stored copy) and has not stored what is not a success: this is the 4xx and
    // the 3xx, which say the sync itself is wrong -- the token, the path, a gateway in the way --
    // and which a stored copy would not mend.
    private def succeeded(request: Request)(response: Response): IO[UnexpectedStatus, Response] =
      if response.status.isSuccess then ZIO.succeed(response)
      else ZIO.fail(UnexpectedStatus(response.status, request.url.path.encode))

    override def liveRequest(request: Request): ZIO[Scope, Throwable, Response] = send(request)

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
