package versola.util

import zio.*
import zio.test.*

import javax.security.auth.x500.X500Principal

object CertificateSubjectSpec extends ZIOSpecDefault:

  private val subject = CertificateSubject(
    distinguishedName = X500Principal("CN=mobile-app,OU=tenant-a,O=Versola").getName(X500Principal.RFC2253),
    commonName = "mobile-app",
    dnsNames = List("mobile-app.clients.versola.test"),
    uris = List("spiffe://versola/clients/mobile-app"),
  )

  /** A request for `subject` whose SAN also carries `extra`, signed by its own key. */
  private def withExtraName(subject: CertificateSubject, extra: org.bouncycastle.asn1.x509.GeneralName): String =
    import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
    import org.bouncycastle.asn1.x509.{Extension, ExtensionsGenerator, GeneralName, GeneralNames}
    val generator = java.security.KeyPairGenerator.getInstance("EC")
    generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    val pair = generator.generateKeyPair()
    val builder = org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(subject.principal, pair.getPublic)
    val extensions = ExtensionsGenerator()
    val names = subject.dnsNames.map(GeneralName(GeneralName.dNSName, _)) ++
      subject.uris.map(GeneralName(GeneralName.uniformResourceIdentifier, _)) :+ extra
    extensions.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toArray))
    builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
    val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate)
    CertificateSubject.pem("CERTIFICATE REQUEST", builder.build(signer).getEncoded)

  def spec = suite("CertificateSubject")(
    test("a request generated for a subject matches exactly it") {
      for generated <- CertificateSubject.generate(subject)
      yield assertTrue(CertificateSubject.matches(generated.csrPem, subject) == Right(()))
    },
    test("a request for another subject does not match") {
      for generated <- CertificateSubject.generate(subject)
      yield assertTrue(
        CertificateSubject.matches(generated.csrPem, subject.copy(distinguishedName = "CN=other")).isLeft,
      )
    },
    test("a request with a name added, or one missing, does not match") {
      for generated <- CertificateSubject.generate(subject)
      yield assertTrue(
        CertificateSubject.matches(generated.csrPem, subject.copy(dnsNames = Nil)).isLeft,
        CertificateSubject.matches(generated.csrPem, subject.copy(dnsNames = List("a.test", "b.test"))).isLeft,
        CertificateSubject.matches(generated.csrPem, subject.copy(uris = Nil)).isLeft,
      )
    },
    test("an IP address subject matches the request generated for it") {
      val ip = subject.copy(dnsNames = Nil, uris = Nil, ipAddresses = List("192.0.2.1"))
      for generated <- CertificateSubject.generate(ip)
      yield assertTrue(
        CertificateSubject.matches(generated.csrPem, ip) == Right(()),
        CertificateSubject.matches(generated.csrPem, ip.copy(ipAddresses = List("192.0.2.2"))).isLeft,
      )
    },
    // A signed request may carry a name of a kind no registration can name; a CA that copies the
    // extension would sign it, so it is no match even though every registered name is present.
    test("a request adding a name of another kind is no match") {
      for generated <- CertificateSubject.generate(subject)
      yield
        val extra = org.bouncycastle.asn1.x509.GeneralName(
          org.bouncycastle.asn1.x509.GeneralName.directoryName, org.bouncycastle.asn1.x500.X500Name("CN=admin"),
        )
        val csr = withExtraName(subject, extra)
        assertTrue(CertificateSubject.matches(csr, subject).isLeft)
    },
    test("text that is not a request is refused, not thrown") {
      assertTrue(CertificateSubject.matches("not a request", subject).isLeft)
    },
    test("the decoded common name has no escaping") {
      assertTrue(CertificateSubject.commonNameOf(X500Principal("""CN=app\,blue,O=Versola""")) == Some("app,blue"))
    },
  )
