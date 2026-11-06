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
    connection(client).request(request)

  /** `client` configured to reach central's mutual-TLS listener: this edge's certificate
    * presented, central's pinned. Every call to central goes through it. The default is the
    * client as given, for the sync client specs' fakes. */
  def connection(client: Client): Client = client

object CentralSyncTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  def live: ZLayer[Scope & EdgeConfig & ConfigSnapshot & EdgeCertificateHolder, Throwable, CentralSyncTokenService] = {
    TokenSource >>>
      ZLayer(ReloadingCache.make[String](ReloadInterval)) >>>
      ZLayer.fromFunction(Impl(_, _, _, _))
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
      certificate: EdgeCertificateHolder,
  ) extends CentralSyncTokenService:
    override def getToken: UIO[String] = cache.get

    // Built per call, not once: where central issues this edge's certificate it is replaced before it expires, and
    // the connection has to present the one that is current. A pin and the files are read, nothing is fetched.
    override def connection(client: Client): Client =
      val ssl =
        for
          mtls <- config.central.mutualTls
          (certificate, privateKey) <- certificate.paths
        yield ClientSSLConfig.FromClientAndServerCert(
          EdgeConfig.pinnedTrust(mtls.trustedCertificates),
          ClientSSLCertConfig.FromClientCertFile(certificate, privateKey),
        )
      ssl.fold(client)(client.ssl)

    override def syncRequest(client: Client, request: Request): ZIO[Scope, Throwable, Response] =
      snapshot.through(request)(connection(client).request(request))
