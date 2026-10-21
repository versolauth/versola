package versola.central.configuration.edges

import versola.central.CentralConfig.ClientCertificateAuthorityConfig
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.{CertificateSubject, EdgeIdentityNames, PrivateClientCertificate, TestCertificates}
import zio.*
import zio.http.Client
import zio.test.*

import java.nio.file.Files

/** Edges and clients are signed by different CAs, so that a client's certificate does not pass the handshake of the
  * edge surface; and where nothing separate is configured, the client issuer is used, as it always was. */
object EdgeCertificateIssuerSpec extends ZIOSpecDefault:

  private val edgeCa = TestCertificates.generate(subject = "CN=edge-ca,O=Versola,C=KZ", ca = true)
  private val clientCa = TestCertificates.generate(subject = "CN=client-ca,O=Versola,C=KZ", ca = true)

  private val subject = EdgeIdentityNames.subject(edgeId = "edge-a", groupId = "default")

  private val authority = ZIO.attemptBlocking:
    val certificate = Files.writeString(Files.createTempFile("edge-ca", ".crt"), edgeCa.certificatePem)
    val key = Files.writeString(Files.createTempFile("edge-ca", ".key"), edgeCa.privateKeyPem)
    ClientCertificateAuthorityConfig(certificate.toString, key.toString, None)

  /** Stands for the issuer of clients' certificates. */
  private val sharedIssuer: ClientCertificateIssuer =
    (_: CertificateSigningRequest) => ZIO.succeed(clientCa.certificatePem)

  private def built(config: CentralConfig) =
    ZIO.service[EdgeCertificateIssuer].provide(
      EdgeCertificateIssuer.live,
      ZLayer.succeed(config),
      ZLayer.succeed(sharedIssuer),
      Client.default,
    )

  def spec = suite("EdgeCertificateIssuer")(
    test("with nothing configured for edges, the client issuer signs them") {
      for issuer <- built(TestCentralConfig.config)
      yield assertTrue(issuer.issuer.eq(sharedIssuer))
    },
    test("with an edge CA central holds the key of, that CA signs the edge, not the client's") {
      for
        ca <- authority
        issuer <- built(TestCentralConfig.config.copy(edgeCertificateAuthority = Some(ca)))
        generated <- CertificateSubject.generate(subject)
        chain <- issuer.issuer.sign(
          CertificateSigningRequest(
            csrPem = generated.csrPem,
            commonName = subject.commonName,
            dnsNames = Nil,
            uris = subject.uris,
            emailAddresses = Nil,
            ipAddresses = Nil,
            validity = 14.days,
          ),
        )
        material <- ZIO.fromEither(PrivateClientCertificate(chain + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
      yield assertTrue(
        !(issuer.issuer.eq(sharedIssuer)),
        scala.util.Try(material.leaf.verify(edgeCa.certificate.getPublicKey)).isSuccess,
        scala.util.Try(material.leaf.verify(clientCa.certificate.getPublicKey)).isFailure,
        material.subjectValues("san_uri") == subject.uris.toSet,
      )
    },
    test("the lifetime asked of the CA defaults to the clients' when no edge backend names one") {
      assertTrue(EdgeCertificateIssuer.validity(TestCentralConfig.config) == CentralConfig.ClientCertificatesConfig().validity)
    },
  ) @@ TestAspect.withLiveClock
