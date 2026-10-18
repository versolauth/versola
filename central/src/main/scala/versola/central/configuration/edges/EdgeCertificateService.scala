package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.configuration.clients.certificates.{
  CertificateSigningRequest,
  ClientCertificateIssuer,
  ClientCertificateRequests,
  ClientCertificateService,
}
import versola.util.CertificateSubject
import zio.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}

/** Issues the certificate an edge presents on central's mutual-TLS listener, and pins it.
  *
  * The edge makes the key and the request; the CA behind [[ClientCertificateIssuer]] signs it, as
  * for an enrolled client (`ClientCertificateService.sign`), and central never sees the key. What
  * differs is what is pinned: central records the issued certificate's thumbprint as the edge's
  * current one before the edge has it, so a renewal needs no registration by anyone.
  *
  * The first certificate cannot be had this way, since asking needs one: the deployment issues it
  * and central's bootstrap registers it.
  */
trait EdgeCertificateService:

  /** Signs `csrPem` for `edgeId`, which has just authenticated with the certificate whose
    * thumbprint is `presented`, and pins the result. Refused unless the request says exactly
    * [[CertificateSubject.forEdge]]. Returns the certificate chain, leaf first. */
  def sign(edgeId: EdgeId, presented: String, csrPem: String): IO[EdgeCertificateService.SigningRefused | Throwable, String]

object EdgeCertificateService:

  /** The request is not for what an edge's certificate must say, or the CA's answer is not. A
    * refusal, not a fault: the edge cannot fix it by retrying. */
  case class SigningRefused(edgeId: EdgeId, reason: String) extends RuntimeException(s"edge '$edgeId': $reason")

  val live: URLayer[ClientCertificateIssuer & EdgeService & CentralConfig, EdgeCertificateService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(issuer: ClientCertificateIssuer, edges: EdgeService, config: CentralConfig) extends EdgeCertificateService:
    private val validity = ClientCertificateService.settingsOf(config).validity

    override def sign(
        edgeId: EdgeId,
        presented: String,
        csrPem: String,
    ): IO[EdgeCertificateService.SigningRefused | Throwable, String] =
      def refused(reason: String) = SigningRefused(edgeId, reason)
      val subject = CertificateSubject.forEdge(edgeId)
      for
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subject)).mapError(refused(_))
        chain <- issuer.sign(ClientCertificateRequests.signingRequest(csrPem, subject, validity))
        leaf <- ZIO.attempt:
          CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
            .iterator.next.asInstanceOf[X509Certificate]
        // What the CA returned must be what was asked for, or the edge would present a
        // certificate its own name is not on.
        _ <- ZIO.fail(refused("the issued certificate does not carry the subject requested"))
          .unless(leaf.getSubjectX500Principal == subject.principal)
        _ <- edges.pinCertificates(edgeId, EdgeCertificate.thumbprint(leaf), Some(presented))
        _ <- ZIO.logInfo(
          s"signed certificate ${leaf.getSerialNumber.toString(16)} for edge '$edgeId', valid until ${leaf.getNotAfter.toInstant}",
        )
      yield chain
