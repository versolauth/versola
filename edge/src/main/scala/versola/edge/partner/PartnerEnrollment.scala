package versola.edge.partner

import versola.edge.EdgeCertificateHolder
import versola.util.{CertificateSubject, EnrollmentToken, SecureRandom, SecurityService}
import zio.*
import zio.http.{Body, Client, Header, MediaType, Request, Scheme, URL}
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Base64

/** What central answered the enrollment with, and what the edge keeps in `central.identity.directory` (`enrolled.json`, owner-only)
  * for every later start: who it is, where central and auth are, and the key it signs for central with (generated here, and never
  * sent anywhere: central has the public half).
  *
  * These are the values the other edge kinds put in the configuration (`id`, `key-id`, `private-key`, `versola-url`, `central.url`,
  * `central.identity.group`); for a partner edge they are read from here instead and the file refuses them (see [[PartnerEnrollment]]).
  */
case class Enrolled(
    edgeId: String,
    group: String,
    keyId: String,
    privateKey: String,
    central: Enrolled.Central,
    auth: Enrolled.Auth,
) derives JsonCodec:

  /** The configuration values this fixes, in the spelling of the file (kebab-case, dotted paths). */
  def derived: Map[String, String] =
    Map(
      "id" -> edgeId,
      "key-id" -> keyId,
      "private-key" -> privateKey,
      "versola-url" -> auth.issuer,
      "central.url" -> central.mtlsUrl,
      "central.identity.group" -> group,
    )

  /** What the file may say otherwise: where auth is reached by this edge's own calls is a matter of the network it runs in. */
  def defaults: Map[String, String] = auth.mtlsUrl.map("versola-internal-url" -> _).toMap

object Enrolled:
  case class Central(mtlsUrl: String) derives JsonCodec
  case class Auth(issuer: String, mtlsUrl: Option[String] = None) derives JsonCodec

  val FileName = "enrolled.json"

  private val OwnerOnlyFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  def read(directory: Path): Task[Option[Enrolled]] =
    ZIO.attemptBlocking(Option.when(Files.exists(directory.resolve(FileName)))(Files.readString(directory.resolve(FileName)))).flatMap:
      case None => ZIO.none
      case Some(text) =>
        ZIO.fromEither(text.fromJson[Enrolled]).mapBoth(
          error => RuntimeException(s"${directory.resolve(FileName)} is not readable: $error"),
          Some(_),
        )

  /** Atomically, and readable by the edge only: it holds the private key. */
  def write(directory: Path, enrolled: Enrolled): Task[Unit] =
    ZIO.attemptBlocking:
      val temporary = Files.createTempFile(directory, ".tmp-", "", OwnerOnlyFile)
      Files.writeString(temporary, enrolled.toJson, StandardCharsets.UTF_8)
      Files.move(temporary, directory.resolve(FileName), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    .unit

/** Registers a partner edge with central by its one token, before there is a configuration to read.
  *
  * A partner edge's `id`, group, the address of central's mutual-TLS listener, the issuer and its signing key are not in its
  * configuration: central tells them in the answer to the enrollment, and they are kept in the identity directory. So the
  * configuration is read in two steps. [[resolve]] is the first: for a partner edge it enrolls (when there is no valid certificate
  * in the directory yet), reads `enrolled.json` and returns a provider that gives those values to the second step, the usual
  * reading of [[versola.edge.EdgeConfig]]; for any other edge it returns the provider as it is.
  */
object PartnerEnrollment:

  /** What the file of a partner edge must not say: central decides them. */
  private val Derived: List[(String, Config[Option[String]])] =
    List(
      "id" -> Config.string("id").optional,
      "key-id" -> Config.string("keyId").optional,
      "private-key" -> Config.string("privateKey").optional,
      "versola-url" -> Config.string("versolaUrl").optional,
      "central.url" -> Config.string("url").nested("central").optional,
      "central.identity.group" -> Config.string("group").nested("identity").nested("central").optional,
    )

  private case class EnrollRequest(token: String, csr: String, signingKey: Json.Obj) derives JsonCodec
  private case class EdgeIdentityResponse(id: String, group: String) derives JsonCodec
  private case class CentralResponse(mtlsUrl: Option[String] = None) derives JsonCodec
  private case class AuthResponse(issuer: Option[String] = None, mtlsUrl: Option[String] = None) derives JsonCodec
  private case class EnrollResponse(certificate: String, edge: EdgeIdentityResponse, central: CentralResponse, auth: AuthResponse) derives JsonCodec

  /** The subject of the request of the first certificate: the edge does not know its own name yet, and central, which resolves it
    * from the token, issues the certificate for the names it chose and takes only the key from the request. */
  private val EnrollingSubject: CertificateSubject =
    CertificateSubject(
      distinguishedName = "CN=enrolling",
      commonName = "enrolling",
      dnsNames = Nil,
      uris = Nil,
      emailAddresses = Nil,
      ipAddresses = Nil,
    )

  def resolve(base: ConfigProvider): ZIO[Client, Throwable, ConfigProvider] =
    base.load(Config.string("mode").optional).flatMap:
      case Some("partner") => resolvePartner(base)
      case _ => ZIO.succeed(base)

  private def resolvePartner(base: ConfigProvider): ZIO[Client, Throwable, ConfigProvider] =
    for
      _ <- refuseDerived(base)
      directoryName <- base.load(Config.string("directory").nested("identity").nested("central").optional)
        .someOrFail(IllegalArgumentException("mode = partner needs central.identity.directory: where the edge keeps what enrollment gave it"))
      directory = Path.of(directoryName)
      holder <- EdgeCertificateHolder.directory(directory)
      held <- holder.material
      now <- Clock.instant
      valid = held.exists(material => !now.isBefore(material.leaf.getNotBefore.toInstant) && now.isBefore(material.leaf.getNotAfter.toInstant))
      enrolled <- Enrolled.read(directory)
      token <- tokenOf(base)
      result <- (enrolled, token) match
        case (Some(known), _) if valid => ZIO.succeed(known)
        case (_, Some(raw)) => enroll(raw, holder, directory)
        case (Some(_), None) =>
          ZIO.fail(
            RuntimeException(
              s"the certificate in $directoryName has expired and there is no enrollment token: ask for a new one in the console and " +
                "set central.identity.enrollment.token-file",
            ),
          )
        case (None, None) =>
          ZIO.fail(
            RuntimeException(
              "this edge is not enrolled and has no enrollment token: create one in the console and set " +
                "central.identity.enrollment.token-file (or token), or restore the identity directory",
            ),
          )
    yield overlay(base, result)

  /** The values the enrollment fixed come before the file, the file before the defaults enrollment suggests. */
  private[partner] def overlay(base: ConfigProvider, enrolled: Enrolled): ConfigProvider =
    ConfigProvider.fromMap(enrolled.derived).kebabCase.orElse(base).orElse(ConfigProvider.fromMap(enrolled.defaults).kebabCase)

  private def refuseDerived(base: ConfigProvider): Task[Unit] =
    ZIO.foreachDiscard(Derived): (name, config) =>
      base.load(config).flatMap: value =>
        ZIO.fail(
          IllegalArgumentException(
            s"$name is not configured on a partner edge: central tells it at enrollment and it is kept in central.identity.directory",
          ),
        ).when(value.isDefined)

  /** The token, from `central.identity.enrollment.token` or the file `token-file` names. None where neither is set. */
  private def tokenOf(base: ConfigProvider): Task[Option[String]] =
    val enrollment = Config.Optional(
      Config.Optional(
        Config.string("token"),
      ).zip(Config.Optional(Config.string("tokenFile"))).nested("enrollment").nested("identity").nested("central"),
    )
    base.load(enrollment).flatMap:
      case Some((Some(token), _)) if token.trim.nonEmpty => ZIO.some(token.trim)
      case Some((_, Some(file))) =>
        ZIO.attemptBlocking(Files.readString(Path.of(file)).trim).map(Some(_).filter(_.nonEmpty))
          .mapError(error => RuntimeException(s"cannot read the enrollment token at $file: ${error.getMessage}"))
      case _ => ZIO.none

  /** Generates the signing key and the key of the TLS certificate (neither leaves this process), trades the token for the certificate,
    * and keeps the answer. The certificate is installed before `enrolled.json` is written, and both before anything else starts. */
  private def enroll(raw: String, holder: EdgeCertificateHolder, directory: Path): ZIO[Client, Throwable, Enrolled] =
    for
      parsed <- ZIO.fromEither(EnrollmentToken.parse(raw)).mapError(reason => IllegalArgumentException(s"not an enrollment token: $reason"))
      enrollUrl <-
        ZIO.fromEither(URL.decode(parsed.enrollUrl)).mapError(_ => IllegalArgumentException("the enrollment address in the token is not a URL"))
      client <- ZIO.service[Client]
      keys <- ZIO.serviceWithZIO[SecurityService](_.generateRsaKeyPair).provide(SecurityService.live, SecureRandom.live)
      generated <- CertificateSubject.generate(EnrollingSubject)
      body = EnrollRequest(token = raw.trim, csr = generated.csrPem, signingKey = keys.toPublicJwk).toJson
      request = Request.post(enrollUrl / "edge" / "enroll", Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))
      result <- ZIO.scoped(client.request(request).flatMap(response => response.body.asString.map(response.status -> _)))
      (status, text) = result
      _ <-
        ZIO.fail(RuntimeException(s"central refused the enrollment with $status${if text.isEmpty then "" else s": $text"}")).unless(status.isSuccess)
      answer <- ZIO.fromEither(text.fromJson[EnrollResponse]).mapError(error =>
        RuntimeException(s"central's answer to the enrollment is not understood: $error"),
      )
      // without these the edge could not start: say so, rather than fail later with a missing key of the configuration
      mtlsUrl <- ZIO.fromOption(answer.central.mtlsUrl)
        .orElseFail(RuntimeException("central did not say where its mutual-TLS listener is: its operator has not configured central's public names"))
      issuer <- ZIO.fromOption(answer.auth.issuer)
        .orElseFail(RuntimeException("central did not say what the issuer of the authorization server is: its operator has not configured it"))
      enrolled = Enrolled(
        edgeId = answer.edge.id,
        group = answer.edge.group,
        keyId = keys.keyId,
        privateKey = Base64.getEncoder.encodeToString(keys.privateKey.getEncoded),
        central = Enrolled.Central(mtlsUrl),
        auth = Enrolled.Auth(issuer, answer.auth.mtlsUrl),
      )
      _ <- holder.install(answer.certificate, generated.privateKeyPem)
      _ <- Enrolled.write(directory, enrolled)
      _ <- ZIO.logInfo(s"enrolled with central as edge '${enrolled.edgeId}' of group '${enrolled.group}'")
    yield enrolled
