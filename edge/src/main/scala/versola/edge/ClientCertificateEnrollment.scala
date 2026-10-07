package versola.edge

import versola.edge.model.ClientId
import versola.util.{CertificateSubject, PrivateClientCertificate}
import zio.*
import zio.http.{Body, Client, Header, MediaType, Request, URL}
import zio.json.*

import java.time.Instant

/** The certificate this edge presents as a client it enrols for (#463): a key pair generated
  * here, which never leaves this process, and a certificate central had its CA sign for a request
  * made with it.
  *
  * Nothing is persisted: a restart enrols again, which costs one signing per client and keeps the
  * key out of any file. Each replica enrols for itself, so each has its own key -- the certificates
  * differ and all carry the subject the client is registered by, which is what auth recognises it by.
  *
  * Driven by the client sync that already runs on an interval: a certificate with less than a third
  * of its lifetime left is replaced the next time the client is looked at, and one that has expired
  * is replaced or the client is not served.
  */
trait ClientCertificateEnrollment:

  /** The certificate for `clientId`, enrolling first when there is none or it is due. Falls back to
    * a certificate that is due but still valid if the new one cannot be had, so a CA outage inside
    * the renewal window is not an outage of the client. */
  def certificateFor(clientId: ClientId, subject: CertificateSubject): Task[PrivateClientCertificate.Material]

object ClientCertificateEnrollment:

  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, ClientCertificateEnrollment] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        config <- ZIO.service[EdgeConfig]
        tokens <- ZIO.service[CentralSyncTokenService]
        enrollment <- make(client, config, tokens)
      yield enrollment

  def make(client: Client, config: EdgeConfig, tokens: CentralSyncTokenService): UIO[ClientCertificateEnrollment] =
    Ref.Synchronized.make(Map.empty[ClientId, Held]).map(Impl(client, config, tokens, _))

  private[edge] case class Held(subject: CertificateSubject, material: PrivateClientCertificate.Material):
    def notBefore: Instant = material.leaf.getNotBefore.toInstant
    def notAfter: Instant = material.leaf.getNotAfter.toInstant

    def valid(now: Instant): Boolean = now.isBefore(notAfter) && !now.isBefore(notBefore)

    /** Due when a third of the lifetime or less is left. */
    def due(now: Instant): Boolean =
      val lifetime = java.time.Duration.between(notBefore, notAfter)
      java.time.Duration.between(now, notAfter).compareTo(lifetime.dividedBy(3)) <= 0

  private case class SignRequest(clientId: String, csr: String) derives JsonCodec
  private case class SignResponse(certificate: String) derives JsonCodec

  private class Impl(
      httpClient: Client,
      config: EdgeConfig,
      tokens: CentralSyncTokenService,
      held: Ref.Synchronized[Map[ClientId, Held]],
  ) extends ClientCertificateEnrollment:

    private val SignUrl: URL = config.central.url / "configuration" / "clients" / "edge-certificate" / "sign"

    override def certificateFor(clientId: ClientId, subject: CertificateSubject): Task[PrivateClientCertificate.Material] =
      // One enrolment at a time, and the same one for every caller that arrives while it runs.
      held.modifyZIO: current =>
        Clock.instant.flatMap: now =>
          current.get(clientId).filter(_.subject == subject) match
            case Some(existing) if existing.valid(now) && !existing.due(now) =>
              ZIO.succeed(existing.material -> current)
            case Some(existing) if existing.valid(now) =>
              enroll(clientId, subject).map(fresh => fresh.material -> current.updated(clientId, fresh))
                .catchAll: error =>
                  ZIO.logWarning(s"renewing the certificate of client '$clientId' failed, keeping the current one until ${existing.notAfter}: $error")
                    .as(existing.material -> current)
            case _ =>
              enroll(clientId, subject).map(fresh => fresh.material -> current.updated(clientId, fresh))

    private def enroll(clientId: ClientId, subject: CertificateSubject): Task[Held] =
      for
        generated <- CertificateSubject.generate(subject)
        token <- tokens.getToken
        body = SignRequest(clientId, generated.csrPem).toJson
        request = Request.post(SignUrl, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        response <- ZIO.scoped(httpClient.request(request).flatMap(r => r.body.asString.map(r.status -> _)))
        (status, text) = response
        _ <- ZIO.fail(RuntimeException(s"central refused the certificate request for client '$clientId' with $status: $text"))
          .unless(status.isSuccess)
        signed <- ZIO.fromEither(text.fromJson[SignResponse]).mapError(error => RuntimeException(s"central's answer is not a certificate: $error"))
        material <- ZIO.fromEither(PrivateClientCertificate(signed.certificate.trim + "\n" + generated.privateKeyPem).material)
          .mapError(reason => RuntimeException(s"the certificate central signed for client '$clientId' $reason"))
      yield Held(subject, material)
