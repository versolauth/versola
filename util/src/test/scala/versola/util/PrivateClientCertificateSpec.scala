package versola.util

import zio.test.*

/** What an edge may be provisioned with to authenticate as an RFC 8705 client, and what it may
  * not be.
  *
  * The subject and key readings below are asserted against the values `auth` compares -- an
  * RFC 4514 subject and the DER `SubjectPublicKeyInfo` -- because a registration this accepts
  * and auth then refuses is worse than no validation at all.
  */
object PrivateClientCertificateSpec extends ZIOSpecDefault:

  private val client = TestCertificates.generate(dnsName = Some("edge-mtls-client.versola.test"))
  private val ec = TestCertificates.generate(subject = "CN=edge-mtls-ec,O=Versola,C=KZ", algorithm = "EC")
  private val impostor = TestCertificates.generate(subject = "CN=impostor,O=Versola,C=KZ")

  def spec = suite("PrivateClientCertificate")(validateSuite, materialSuite)

  private val validateSuite = suite("validate")(
    test("accepts a certificate and the key that signed it") {
      assertTrue(PrivateClientCertificate.validate(client.bundle).isRight)
    },
    test("accepts an EC pair, so the reader is not RSA-only in practice") {
      assertTrue(PrivateClientCertificate.validate(ec.bundle).isRight)
    },
    test("refuses a key that belongs to a different certificate") {
      // The registration most worth catching: both halves are valid, and the handshake they
      // configure fails where nothing can say why.
      val mismatched = s"${client.certificatePem}\n${impostor.privateKeyPem}"
      assertTrue(
        PrivateClientCertificate.validate(mismatched)
          .left.exists(_.contains("does not belong to its certificate")),
      )
    },
    test("refuses a certificate with no key, which presents nothing") {
      assertTrue(
        PrivateClientCertificate.validate(client.certificatePem)
          .left.exists(_.contains("PRIVATE KEY")),
      )
    },
    test("refuses a key with no certificate") {
      assertTrue(
        PrivateClientCertificate.validate(client.privateKeyPem)
          .left.exists(_.contains("CERTIFICATE")),
      )
    },
    test("refuses a PKCS#1 key, which the TLS stack cannot read back") {
      // Named rather than reported as unparsable: what an operator has is a key in the wrong
      // container, and `openssl pkcs8` is the whole of the fix.
      val pkcs1 = client.bundle.replace("PRIVATE KEY", "RSA PRIVATE KEY")
      assertTrue(
        PrivateClientCertificate.validate(pkcs1)
          .left.exists(reason => reason.contains("RSA PRIVATE KEY") && reason.contains("PKCS#8")),
      )
    },
    test("refuses two keys rather than presenting whichever came first") {
      val twoKeys = s"${client.bundle}\n${impostor.privateKeyPem}"
      assertTrue(
        PrivateClientCertificate.validate(twoKeys)
          .left.exists(_.contains("exactly one")),
      )
    },
    test("refuses a certificate block that is not a certificate") {
      val nonsense = s"-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----\n${client.privateKeyPem}"
      assertTrue(
        PrivateClientCertificate.validate(nonsense)
          .left.exists(_.contains("X.509")),
      )
    },
  )

  private val materialSuite = suite("material")(
    test("hands the two halves back separately, as the TLS stack takes them") {
      val material = PrivateClientCertificate(client.bundle).material
      assertTrue(
        material.map(_.certificateChain.contains("BEGIN CERTIFICATE")) == Right(true),
        material.map(_.certificateChain.contains("PRIVATE KEY")) == Right(false),
        material.map(_.privateKeyPem.contains("BEGIN PRIVATE KEY")) == Right(true),
      )
    },
    test("keeps the whole chain, leaf first, for a certificate issued under one") {
      val chained = s"${client.certificatePem}\n${impostor.certificatePem}\n${client.privateKeyPem}"
      val material = PrivateClientCertificate(chained).material
      assertTrue(
        material.map(_.certificateChain.split("BEGIN CERTIFICATE").length) == Right(3),
        // The leaf is what authenticates: auth reads the first certificate of a forwarded
        // chain, so the pair has to be checked against that one.
        material.map(_.leaf.getSubjectX500Principal.getName) == Right(client.certificate.getSubjectX500Principal.getName),
      )
    },
    test("reads the subject as the RFC 4514 string a subject_dn registration is compared to") {
      assertTrue(
        PrivateClientCertificate(client.bundle).material.map(_.subjectDn) == Right(client.subjectDn),
        PrivateClientCertificate(client.bundle).material.map(_.subjectValues("subject_dn")) ==
          Right(Set(client.subjectDn)),
      )
    },
    test("reads a dNSName as san_dns, and nothing as the other subject types") {
      val material = PrivateClientCertificate(client.bundle).material
      assertTrue(
        material.map(_.subjectValues("san_dns")) == Right(Set("edge-mtls-client.versola.test")),
        material.map(_.subjectValues("san_uri")) == Right(Set.empty[String]),
      )
    },
    test("finds its key in a set that publishes it, and not in one that does not") {
      val material = PrivateClientCertificate(client.bundle).material
      assertTrue(
        material.map(_.publishedIn(client.jwks)) == Right(true),
        material.map(_.publishedIn(impostor.jwks)) == Right(false),
      )
    },
  )
