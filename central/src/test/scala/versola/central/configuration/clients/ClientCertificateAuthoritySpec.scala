package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.tenants.TenantId
import versola.util.TestCertificates
import zio.*
import zio.test.*

import java.nio.file.Files
import java.security.cert.{CertPathValidator, CertificateFactory, PKIXParameters, TrustAnchor, X509Certificate}
import java.security.interfaces.ECPublicKey
import java.time.Instant
import scala.jdk.CollectionConverters.*

object ClientCertificateAuthoritySpec extends ZIOSpecDefault:

  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("mobile-app")

  private def authorityOf(ca: TestCertificates.Generated, validityDays: Int = 30) =
    ClientCertificateAuthority.fromPem(ca.certificatePem, ca.privateKeyPem, validityDays, Instant.now())

  private def issue(ca: TestCertificates.Generated) =
    ZIO.fromEither(authorityOf(ca)).mapError(RuntimeException(_))
      .flatMap(_.issue(tenantId, clientId))
      .someOrFail(RuntimeException("a configured authority issued nothing"))

  private def leafOf(issued: ClientCertificateAuthority.Issued): X509Certificate =
    issued.certificate.material.toOption.get.leaf

  /** What auth's mutual-TLS listener does with the leaf it is handed: PKIX validation against
    * the configured anchors, which is the whole of what makes an issued certificate usable. */
  private def chainsTo(leaf: X509Certificate, ca: X509Certificate): Boolean =
    val parameters = PKIXParameters(Set(TrustAnchor(ca, null)).asJava)
    parameters.setRevocationEnabled(false)
    scala.util.Try(
      CertPathValidator.getInstance("PKIX").validate(
        CertificateFactory.getInstance("X.509").generateCertPath(List(leaf).asJava),
        parameters,
      ),
    ).isSuccess

  def spec = suite("ClientCertificateAuthority")(
    test("issues a client certificate auth's listener would accept, recognised by its subject") {
      val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
      for issued <- issue(ca)
      yield
        val leaf = leafOf(issued)
        assertTrue(
          chainsTo(leaf, ca.certificate),
          issued.subjectDn == "CN=mobile-app,OU=tenant-a,O=Versola",
          leaf.getBasicConstraints == -1,
          leaf.getExtendedKeyUsage.asScala.contains("1.3.6.1.5.5.7.3.2"),
          leaf.getPublicKey.isInstanceOf[ECPublicKey],
        )
    },
    test("signs with an EC authority as readily as with an RSA one") {
      val ca = TestCertificates.generate(subject = "CN=EC Client CA", algorithm = "EC", ca = true)
      for issued <- issue(ca)
      yield assertTrue(chainsTo(leafOf(issued), ca.certificate))
    },
    test("never issues past the authority's own expiry") {
      // TestCertificates' CA expires in a day; thirty are asked for.
      val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
      for issued <- issue(ca)
      yield assertTrue(!leafOf(issued).getNotAfter.after(ca.certificate.getNotAfter))
    },
    test("issues a fresh key and serial every time") {
      val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
      for
        first <- issue(ca)
        second <- issue(ca)
      yield assertTrue(
        leafOf(first).getSerialNumber != leafOf(second).getSerialNumber,
        leafOf(first).getPublicKey != leafOf(second).getPublicKey,
      )
    },
    test("issues nothing when no authority is configured") {
      for issued <- ClientCertificateAuthority.Unconfigured.issue(tenantId, clientId)
      yield assertTrue(issued.isEmpty)
    },
    suite("refuses an authority that could not issue")(
      test("a certificate that is not a CA") {
        val leaf = TestCertificates.generate(subject = "CN=Not A CA")
        assertTrue(authorityOf(leaf).left.exists(_.contains("not a certificate authority")))
      },
      test("a key that does not belong to the certificate") {
        val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
        val other = TestCertificates.generate(subject = "CN=Other CA", ca = true)
        val result = ClientCertificateAuthority.fromPem(ca.certificatePem, other.privateKeyPem, 30, Instant.now())
        assertTrue(result.left.exists(_.contains("does not belong")))
      },
      test("a validity that is not positive") {
        val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
        assertTrue(authorityOf(ca, validityDays = 0).left.exists(_.contains("validity-days")))
      },
      test("an expired certificate") {
        val ca = TestCertificates.generate(subject = "CN=Client CA", ca = true)
        val later = Instant.now().plus(java.time.Duration.ofDays(2))
        val result = ClientCertificateAuthority.fromPem(ca.certificatePem, ca.privateKeyPem, 30, later)
        assertTrue(result.left.exists(_.contains("expired")))
      },
    ),
    test("load names the configuration when the files cannot back an authority") {
      val leaf = TestCertificates.generate(subject = "CN=Not A CA")
      for
        certificate <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("ca", ".crt"), leaf.certificatePem))
        key <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("ca", ".key"), leaf.privateKeyPem))
        result <- ClientCertificateAuthority.load(
          CentralConfig.ClientCertificateAuthorityConfig(certificate.toString, key.toString, None),
        ).either
        _ <- ZIO.attemptBlocking { Files.delete(certificate); Files.delete(key) }
      yield assertTrue(result.left.exists(_.getMessage.startsWith("client-certificate-authority: ")))
    },
  ) @@ TestAspect.withLiveClock
