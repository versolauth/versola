package versola.edge

import versola.util.{CacheSource, ConfigSnapshot, JWT, ReloadingCache}
import zio.http.{Client, ClientSSLCertConfig, ClientSSLConfig, Request, Response}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

import java.security.PrivateKey
import java.util.concurrent.atomic.AtomicReference

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

  /** The private keys a secret central encrypted for this edge may be decrypted with, newest first: the key it signs for
    * central with and, for the moment after a renewal replaced it, the one before. `fallback` is the key of the configuration, for
    * the sync client specs' fakes. */
  def decryptionKeys(fallback: PrivateKey): UIO[List[PrivateKey]] = ZIO.succeed(List(fallback))

  /** From now on the token is signed with this key (a partner edge's renewal sent central its public half). The default does nothing:
    * the key of the other edges is the one of the configuration. */
  def switchKey(keyId: String, privateKey: PrivateKey): UIO[Unit] = ZIO.unit

object CentralSyncTokenService:
  /** `decrypt` with the first of `keys` it succeeds with: a secret central encrypted just before this edge's key was replaced is
    * for the key before. Fails with the error of the newest key (the one a misconfigured edge should be told about). */
  def firstDecrypting[A](keys: List[PrivateKey])(decrypt: PrivateKey => Task[A]): Task[A] =
    keys match
      case Nil => ZIO.fail(IllegalStateException("this edge holds no key to decrypt with"))
      case newest :: older =>
        def attempt(remaining: List[PrivateKey], firstError: Throwable): Task[A] =
          remaining match
            case Nil => ZIO.fail(firstError)
            case key :: rest => decrypt(key).catchAll(_ => attempt(rest, firstError))
        decrypt(newest).catchAll(error => attempt(older, error))

  private val TokenTtl = 10.minutes
  private val ReloadInterval = 9.minutes

  /** The key the token is signed with, and the one before it for what central encrypted just before a switch. */
  private[edge] case class Keys(keyId: String, current: PrivateKey, previous: Option[PrivateKey])

  def live: ZLayer[Scope & EdgeConfig & ConfigSnapshot & EdgeCertificateHolder, Throwable, CentralSyncTokenService] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[EdgeConfig]
        snapshot <- ZIO.service[ConfigSnapshot]
        certificate <- ZIO.service[EdgeCertificateHolder]
        keys = AtomicReference(Keys(config.keyId, config.privateKey, None))
        source = tokenSource(config, keys)
        cache <- ReloadingCache.make[String](ReloadInterval).provideSomeLayer[Scope](ZLayer.succeed(source))
      yield Impl(cache, config, snapshot, certificate, keys, source)

  private def tokenSource(config: EdgeConfig, keys: AtomicReference[Keys]): CacheSource[String] =
    new CacheSource[String]:
      override def getAll: Task[String] =
        val signing = keys.get
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
            keyId = signing.keyId,
            privateKey = signing.current,
          ),
          headers = Map("edge_id" -> config.id),
        )

  class Impl(
      cache: ReloadingCache[String],
      config: EdgeConfig,
      snapshot: ConfigSnapshot,
      certificate: EdgeCertificateHolder,
      keys: AtomicReference[Keys],
      source: CacheSource[String],
  ) extends CentralSyncTokenService:
    override def decryptionKeys(fallback: PrivateKey): UIO[List[PrivateKey]] =
      ZIO.succeed(keys.get).map(held => held.current :: held.previous.toList)

    override def switchKey(keyId: String, privateKey: PrivateKey): UIO[Unit] =
      // the token in hand is signed with the key central no longer knows: a new one now, not at the next reload
      ZIO.succeed(keys.updateAndGet(held => Keys(keyId, privateKey, Some(held.current)))) *>
        source.getAll.flatMap(cache.set).ignoreLogged

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
