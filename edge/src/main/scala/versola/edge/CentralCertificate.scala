package versola.edge

import versola.util.http.Observability
import versola.util.{CertificateSubject, PrivateClientCertificate}
import zio.*
import zio.http.{Body, ClientSSLConfig, Header, MediaType, Request, Response, URL}
import zio.json.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant

/** The certificate this edge presents on central's mutual-TLS listener, and the renewal that keeps it
  * valid.
  *
  * The first certificate comes from the deployment: the files `central.mutual-tls` names. Every
  * later one is asked of central: a key pair generated here, which never leaves this process, and a
  * request for exactly [[CertificateSubject.forEdge]], sent over the connection the current
  * certificate authenticates. Central pins what it signs before answering, beside the certificate the
  * request was made with, so the switch needs no coordination and a lost answer is simply asked
  * again.
  *
  * Renewal is attempted when a third of the lifetime or less is left, and a failure leaves the current
  * certificate in use until it expires -- a CA outage inside the window is not an outage of the sync.
  * What is presented lives in `ClientCertificateFiles`'s directory like every other client
  * certificate this edge holds.
  */
trait CentralCertificate:

  /** What a connection to central is made with: this edge's current certificate and the pinned
    * central. */
  def ssl: UIO[ClientSSLConfig]

  /** Replaces the certificate when it is due. A failure is raised, with the old one kept. */
  def renewIfDue: Task[Unit]

object CentralCertificate:

  type Send = (ClientSSLConfig, Request) => ZIO[Scope, Throwable, Response]

  /** How often the certificate is looked at. Local: nothing is sent unless one is due. */
  val CheckInterval: Duration = 10.minutes

  def make(
      config: EdgeConfig,
      files: ClientCertificateFiles,
      token: UIO[String],
      send: Send,
  ): Task[CentralCertificate] =
    ZIO.fromOption(config.central.mutualTls).orElseFail(IllegalArgumentException("central.mutual-tls is not configured")).flatMap: mtls =>
      for
        material <- load(mtls)
        presented <- files.present(material)
        current <- Ref.Synchronized.make(Held(material, ClientSSLConfig.FromClientAndServerCert(EdgeConfig.pinnedTrust(mtls.trustedCertificates), presented)))
      yield Impl(config, files, token, send, mtls, current)

  /** Reads the files the deployment put there, failing startup when they do not hold a usable
    * certificate rather than at the first sync. */
  private def load(mtls: EdgeConfig.CentralMutualTls): Task[PrivateClientCertificate.Material] =
    for
      certificate <- ZIO.attemptBlocking(Files.readString(Path.of(mtls.certificate), StandardCharsets.UTF_8))
      key <- ZIO.attemptBlocking(Files.readString(Path.of(mtls.privateKey), StandardCharsets.UTF_8))
      material <- ZIO.fromEither(PrivateClientCertificate(certificate.trim + "\n" + key).material)
        .mapError(reason => IllegalArgumentException(s"central.mutual-tls: the certificate ${mtls.certificate} $reason"))
    yield material

  /** The renewal loop, for the life of the scope. Its failures are logged, not raised: nothing
    * waits on it, and the current certificate serves until it does not. */
  def renewing(certificate: CentralCertificate): URIO[Scope, Unit] =
    certificate.renewIfDue
      .catchAll(error =>
        Observability.setError("edge_central_certificate_renewal_failed", Some(error.getMessage)) *>
          ZIO.logWarning(s"renewing this edge's certificate for central failed, keeping the current one: ${error.getMessage}"),
      )
      .repeat(Schedule.spaced(CheckInterval))
      .unit
      .forkScoped
      .unit

  private case class Held(material: PrivateClientCertificate.Material, ssl: ClientSSLConfig):
    def notBefore: Instant = material.leaf.getNotBefore.toInstant
    def notAfter: Instant = material.leaf.getNotAfter.toInstant

    /** Due when a third of the lifetime or less is left. */
    def due(now: Instant): Boolean =
      val lifetime = java.time.Duration.between(notBefore, notAfter)
      java.time.Duration.between(now, notAfter).compareTo(lifetime.dividedBy(3)) <= 0

  private case class SignRequest(csr: String) derives JsonCodec
  private case class SignResponse(certificate: String) derives JsonCodec

  private class Impl(
      config: EdgeConfig,
      files: ClientCertificateFiles,
      token: UIO[String],
      send: Send,
      mtls: EdgeConfig.CentralMutualTls,
      current: Ref.Synchronized[Held],
  ) extends CentralCertificate:

    private val SignUrl: URL = config.central.url / "edge" / "certificate" / "sign"

    override def ssl: UIO[ClientSSLConfig] = current.get.map(_.ssl)

    override def renewIfDue: Task[Unit] =
      current.updateZIO: held =>
        Clock.instant.flatMap: now =>
          if held.due(now) then renew(held) else ZIO.succeed(held)

    private def renew(held: Held): Task[Held] =
      for
        generated <- CertificateSubject.generate(CertificateSubject.forEdge(config.id))
        bearer <- token
        request = Request.post(SignUrl, Body.fromString(SignRequest(generated.csrPem).toJson))
          .addHeader(Header.Authorization.Bearer(bearer))
          .addHeader(Header.ContentType(MediaType.application.json))
        answer <- ZIO.scoped(send(held.ssl, request).flatMap(r => r.body.asString.map(r.status -> _)))
        (status, text) = answer
        _ <- ZIO.fail(RuntimeException(s"central refused the certificate request with $status: $text")).unless(status.isSuccess)
        signed <- ZIO.fromEither(text.fromJson[SignResponse]).mapError(error => RuntimeException(s"central's answer is not a certificate: $error"))
        material <- ZIO.fromEither(PrivateClientCertificate(signed.certificate.trim + "\n" + generated.privateKeyPem).material)
          .mapError(reason => RuntimeException(s"the certificate central signed $reason"))
        presented <- files.present(material)
        _ <- ZIO.logInfo(s"renewed this edge's certificate for central, valid until ${material.leaf.getNotAfter.toInstant}")
      yield Held(material, ClientSSLConfig.FromClientAndServerCert(EdgeConfig.pinnedTrust(mtls.trustedCertificates), presented))
