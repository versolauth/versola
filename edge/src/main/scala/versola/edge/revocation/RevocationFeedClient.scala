package versola.edge.revocation

import versola.edge.{ClientCertificateFiles, EdgeConfig, EdgeIdentity}
import versola.util.revocation.{Revocation, RevocationKey}
import zio.http.*
import zio.json.*
import zio.{Duration, Task, ZIO, ZLayer}

import java.time.Instant

/** The pull half of how an edge hears of revocations: auth's `GET /revocations`, called over the same mutual-TLS
  * channel and with the same certificate as the edge's `/userinfo` call (`SSOClient`).
  */
trait RevocationFeedClient:
  /** One page after `after` (`""` is the beginning), at most `limit` rows. */
  def fetch(after: String, limit: Int): Task[RevocationFeedClient.Page]

object RevocationFeedClient:
  /** @param cursor where the next read resumes, as auth gave it; handed back unchanged.
    * @param more whether auth says there is another page; `None` from an auth that does not say, when the length of
    *             the page against the limit asked for is all there is to go on.
    */
  case class Page(revocations: List[Revocation], cursor: String, more: Option[Boolean])

  /** Auth answered, and not with a page: `404` from a version that has no feed, `403` where it does not take this
    * edge's certificate for an edge's. Told apart from a failure to reach auth, because the remedy is not a retry.
    */
  case class Refused(status: Int)
    extends RuntimeException(
      s"auth refused the revocation feed with status $status: " + (status match
        case 404 => "this auth serves no feed (an older version), so revocations arrive by notification only"
        case 403 => "auth does not take this edge's certificate for an edge it trusts"
        case _ => "unexpected"),
    )

  private case class Entry(key: String, exp: Long, before: Option[Long]) derives JsonDecoder

  private case class Body(revocations: List[Entry], cursor: String, more: Option[Boolean]) derives JsonDecoder

  val live: ZLayer[Client & EdgeConfig & ClientCertificateFiles & EdgeIdentity, Nothing, RevocationFeedClient] =
    ZLayer.fromFunction((client: Client, config: EdgeConfig, files: ClientCertificateFiles, identity: EdgeIdentity) =>
      Impl(client, config, files, identity),
    )

  /** The feed of another `auth` than the configured one: a cell's (versolauth/versola#559). Every cell keeps its own
    * list, so an edge that serves users of several cells reads each of them. */
  trait Factory:
    def at(base: URL, trust: Option[ClientSSLConfig]): RevocationFeedClient

  object Factory:
    val live: ZLayer[Client & EdgeConfig & ClientCertificateFiles & EdgeIdentity, Nothing, Factory] =
      ZLayer.fromFunction((client: Client, config: EdgeConfig, files: ClientCertificateFiles, identity: EdgeIdentity) =>
        new Factory:
          override def at(base: URL, trust: Option[ClientSSLConfig]): RevocationFeedClient =
            Impl(client, config.revocation.feed.timeout, files, identity, Some(base -> trust)),
      )

  /** @param route the mutual-TLS listener the feed is read from and the pins it is authenticated by. */
  class Impl(
      client: Client,
      timeout: Duration,
      files: ClientCertificateFiles,
      identity: EdgeIdentity,
      route: Option[(URL, Option[ClientSSLConfig])],
  ) extends RevocationFeedClient:

    /** The feed of the `auth` the edge is configured for. The trust is resolved once: pinning several certificates
      * writes a bundle file, which is not something to do every poll. */
    def this(client: Client, config: EdgeConfig, files: ClientCertificateFiles, identity: EdgeIdentity) =
      this(
        client,
        config.revocation.feed.timeout,
        files,
        identity,
        config.native.map(native =>
          native.authMutualTlsUrl -> Option.when(native.trustedCertificates.nonEmpty)(EdgeConfig.pinnedTrust(native.trustedCertificates)),
        ),
      )

    override def fetch(after: String, limit: Int): Task[Page] =
      for
        (base, pinned) <- ZIO.fromOption(route)
          .orElseFail(IllegalStateException("the revocation feed needs a mutual-TLS listener of auth to read from"))
        _ <- ZIO.fail(IllegalStateException(s"'${base.encode}' is reached over plaintext, so no certificate can be presented"))
          .unless(base.scheme.contains(Scheme.HTTPS))
        trust <- ZIO.fromOption(pinned)
          .orElseFail(IllegalStateException(s"no trust anchors are configured for auth's mutual-TLS listener '${base.encode}'"))
        certificate <- identity.certificate.someOrFail(IllegalStateException("this edge holds no certificate yet"))
        certificateConfig <- files.present(certificate)
        connection = ClientSSLConfig.FromClientAndServerCert(trust, certificateConfig)
        url = (base / "revocations").addQueryParams(QueryParams("after" -> after, "limit" -> limit.toString))
        page <- ZIO.scoped:
          client.ssl(connection).request(Request.get(url)).flatMap: response =>
            if response.status == Status.Ok then
              response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[Body]).mapError(IllegalStateException(_)))
            else response.body.asString.ignore *> ZIO.fail(Refused(response.status.code))
        .timeoutFail(java.util.concurrent.TimeoutException(s"the revocation feed did not answer within $timeout"))(timeout)
      yield Page(
        revocations = page.revocations.flatMap(entry =>
          RevocationKey.decode(entry.key).map(key =>
            Revocation(key = key, expiresAt = Instant.ofEpochSecond(entry.exp), issuedBefore = entry.before.map(Instant.ofEpochSecond)),
          ),
        ),
        cursor = page.cursor,
        more = page.more,
      )
