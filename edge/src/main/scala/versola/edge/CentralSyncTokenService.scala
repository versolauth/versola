package versola.edge

import versola.util.{CacheSource, ConfigSnapshot, JWT, ReloadingCache}
import zio.http.{Client, ClientSSLCertConfig, ClientSSLConfig, Request, Response}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait CentralSyncTokenService:
  def getToken: UIO[String]

  /** Sends a configuration sync request, already carrying the token, to central. The live
    * service sends it through the [[ConfigSnapshot]]; this default sends it straight, for the
    * sync client specs' fakes, which implement [[getToken]] only.
    */
  def syncRequest(client: Client, request: Request): ZIO[Scope, Throwable, Response] =
    connection(client).flatMap(_.request(request))

  /** `client` configured to reach central's mutual-TLS listener: this edge's certificate
    * presented, central's pinned. Every call to central goes through it. The default is the
    * client as given, for the sync client specs' fakes. */
  def connection(client: Client): UIO[Client] = ZIO.succeed(client)

object CentralSyncTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & EdgeConfig & ConfigSnapshot & ClientCertificateFiles & Client, Throwable, CentralSyncTokenService] = {
    val tokens = TokenSource >>> ZLayer(ReloadingCache.make[String](ReloadInterval))
    // Built after the token and the certificate it renews with: the renewal signs its request
    // with the same token every sync call carries.
    tokens >+> ZLayer.fromZIO(
      for
        config <- ZIO.service[EdgeConfig]
        files <- ZIO.service[ClientCertificateFiles]
        client <- ZIO.service[Client]
        cache <- ZIO.service[ReloadingCache[String]]
        certificate <- CentralCertificate.make(config, files, cache.get, (ssl, request) => client.ssl(ssl).request(request))
        _ <- CentralCertificate.renewing(certificate)
      yield certificate,
    ) >>> ZLayer.fromFunction(Impl(_, _, _, _))
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
      snapshot: ConfigSnapshot,
      certificate: CentralCertificate,
  ) extends CentralSyncTokenService:
    override def getToken: UIO[String] = cache.get

    override def connection(client: Client): UIO[Client] = certificate.ssl.map(client.ssl)

    override def syncRequest(client: Client, request: Request): ZIO[Scope, Throwable, Response] =
      snapshot.through(request)(connection(client).flatMap(_.request(request)))
