package versola.edge.partner

import versola.edge.EdgeConfig
import zio.http.{Scheme, URL}
import zio.json.*
import zio.{Task, ZIO}

import java.nio.file.{Files, Path}

/** What the operator hands the partner when an edge is created (`POST /configuration/partners`, `POST /partner/api/edges`): who the
  * edge is, where central's enrollment and mutual-TLS listeners and auth are, the key it signs its token with and the one-time
  * token that trades for its first certificate (#962 §3).
  *
  * The edge consumes it in two ways. Its enrollment reads the address central's enrollment is at and the token from it when
  * `central.identity.enrollment` names none (`EdgeIdentityEnrollment`), and every start checks that the bundle and the rest of
  * the configuration describe the same edge ([[disagreements]]): the values of `id`, `key-id`, `private-key`, `central.url`,
  * `versola-url` and `central.identity.group` come out of it, and one copied from the bundle of another edge or group is a
  * mistake that otherwise shows only as central turning the edge away.
  *
  * `token` is central's name for the token; `enrollmentToken` is accepted as well, the name the design issue gave it.
  */
case class BootstrapBundle(
    edgeId: String,
    group: String,
    central: BootstrapBundle.Central,
    auth: BootstrapBundle.Auth,
    signingKey: Option[BootstrapBundle.SigningKey],
    token: Option[String],
    enrollmentToken: Option[String],
) derives JsonCodec:

  /** The token for the first certificate, in whichever of the two names it came. */
  def enrollment: Option[String] = token.orElse(enrollmentToken).map(_.trim).filter(_.nonEmpty)

  /** Where central's enrollment is (plain TLS: the edge has no certificate yet). Absent where the operator configured no public
    * name for it. */
  def enrollUrl: Either[String, URL] =
    central.enrollUrl.toRight("the bundle names no central.enrollUrl").flatMap(raw => URL.decode(raw).left.map(_ => "central.enrollUrl is not a URL"))
      .filterOrElse(_.scheme.contains(Scheme.HTTPS), "central.enrollUrl must be https")

  /** What in this configuration is not what the bundle says. Empty when they describe the same edge. */
  def disagreements(config: EdgeConfig): List[String] =
    def origin(url: URL): String = url.encode.stripSuffix("/")
    def sameUrl(raw: Option[String], url: URL): Boolean = raw.forall(value => URL.decode(value).toOption.exists(origin(_) == origin(url)))
    List(
      Option.unless(edgeId == config.id.toString)(s"the bundle is for edge '$edgeId', this edge is '${config.id}'"),
      Option.unless(config.central.identity.forall(_.group == group))(
        s"the bundle is for group '$group', central.identity.group is '${config.central.identity.fold("")(_.group)}'",
      ),
      Option.unless(signingKey.forall(_.keyId == config.keyId))("the key id of the bundle is not key-id"),
      Option.unless(sameUrl(central.mtlsUrl, config.central.url))("central.mtlsUrl of the bundle is not central.url"),
      Option.unless(sameUrl(auth.issuer, config.versolaUrl))("auth.issuer of the bundle is not versola-url"),
    ).flatten

object BootstrapBundle:
  case class Central(enrollUrl: Option[String], mtlsUrl: Option[String], pins: Option[List[String]]) derives JsonCodec
  case class Auth(issuer: Option[String], mtlsUrl: Option[String]) derives JsonCodec

  /** The key the edge's token to central is signed with; the same as `private-key` of the configuration, spelled in the URL-safe
    * alphabet (see `PostgresEdgeApp`'s reading of that key). */
  case class SigningKey(keyId: String, privateKey: String) derives JsonCodec

  def parse(text: String): Either[String, BootstrapBundle] =
    text.fromJson[BootstrapBundle].left.map(error => s"not a bootstrap bundle: $error")

  def read(path: String): Task[BootstrapBundle] =
    ZIO.attemptBlocking(Files.readString(Path.of(path)))
      .mapError(error => RuntimeException(s"cannot read the bootstrap bundle at $path: ${error.getMessage}"))
      .flatMap(text => ZIO.fromEither(parse(text)).mapError(RuntimeException(_)))
