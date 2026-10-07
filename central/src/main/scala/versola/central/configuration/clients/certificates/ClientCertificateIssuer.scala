package versola.central.configuration.clients.certificates

import versola.central.CentralConfig
import zio.*
import zio.http.Client

/** A certificate signing request for an edge-fronted client's certificate (#440).
  *
  * @param csrPem the PKCS#10 request, PEM-encoded. Its subject and subjectAltName already carry
  *   everything the issued certificate must name; a backend must not add to or change them.
  * @param commonName the request's subject common name, for backends that need it separately
  *   (a step-ca one-time token names it).
  * @param validity how long the certificate should be valid. A backend may issue a shorter one
  *   if its own policy says so, never a longer one, and the result's `notAfter` is what counts.
  */
case class CertificateSigningRequest(
    csrPem: String,
    commonName: String,
    dnsNames: List[String],
    uris: List[String],
    emailAddresses: List[String],
    ipAddresses: List[String],
    validity: Duration,
)

/** Signs client certificates with a CA central does not hold the key of: cert-manager on
  * Kubernetes (`CertManagerIssuer`), step-ca elsewhere (`StepCaIssuer`). Central generates the
  * client's key pair and request; this is the only thing the CA is asked to do.
  */
trait ClientCertificateIssuer:

  /** @return the PEM certificate chain, leaf first, with any intermediates the CA returned;
    *   never the root. */
  def sign(request: CertificateSigningRequest): Task[String]

object ClientCertificateIssuer:

  /** Raised when something asks for a certificate on a central with no `client-certificates`
    * configuration. */
  case object NotConfigured extends RuntimeException(
    "central has no `client-certificates` configuration, so it cannot issue client certificates",
  )

  val notConfigured: ClientCertificateIssuer =
    (_: CertificateSigningRequest) => ZIO.fail(NotConfigured)

  /** The backend `client-certificates` names (exactly one of `step-ca` and `cert-manager`), else the
    * `client-certificate-authority` CA central holds the key of; with neither, signing fails with
    * [[NotConfigured]] when asked. */
  val live: ZLayer[CentralConfig & Client, Throwable, ClientCertificateIssuer] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CentralConfig]
        client <- ZIO.service[Client]
        issuer <- (config.certificateBackends, config.clientCertificateAuthority) match
          case (Some(settings), _) =>
            (settings.stepCa, settings.certManager) match
              case (Some(stepCa), None) => StepCaIssuer.make(stepCa, client)
              case (None, Some(certManager)) => CertManagerIssuer.make(certManager, client)
              case _ =>
                ZIO.fail(IllegalArgumentException(
                  "`client-certificates` names both `step-ca` and `cert-manager`; it must name exactly one",
                ))
          // The earlier setting: central holds the CA's own key. Kept for a deployment that has it.
          case (None, Some(authority)) => LocalCaIssuer.make(authority)
          case (None, None) => ZIO.succeed(notConfigured)
      yield issuer
