package versola.central.configuration.clients.certificates

import versola.central.CentralConfig.ClientCertificateAuthorityConfig
import versola.central.configuration.clients.{ClientId, MutualTlsAuth, MutualTlsSubjectType}
import versola.central.configuration.tenants.TenantId
import versola.util.{CertificateSubject, EdgeIdentityNames, PrivateClientCertificate, TestCertificates}
import zio.*
import zio.test.*

import java.nio.file.Files

object LocalCaIssuerSpec extends ZIOSpecDefault:

  private val ca = TestCertificates.generate(subject = "CN=client-ca,O=Versola,C=KZ", ca = true)

  private val issuer =
    for
      certificate <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("ca", ".crt"), ca.certificatePem))
      key <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("ca", ".key"), ca.privateKeyPem))
      issuer <- LocalCaIssuer.make(ClientCertificateAuthorityConfig(certificate.toString, key.toString, None))
    yield issuer

  private def issued(auth: MutualTlsAuth, validity: Duration = 14.days) =
    for
      issuer <- issuer
      subject <- ZIO.fromEither(ClientCertificateRequests.subjectFor(ClientId("mobile-app"), auth)).mapError(RuntimeException(_))
      generated <- ClientCertificateRequests.generate(subject, validity)
      leaf <- issuer.sign(generated.request)
      material <- ZIO.fromEither(PrivateClientCertificate(leaf + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
    yield material

  def spec = suite("LocalCaIssuer")(
    test("signs a request with the CA's key, keeping the subject and SAN the request carries") {
      for material <- issued(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "mobile-app.clients.versola.test"))
      yield assertTrue(
        material.subjectValues("san_dns") == Set("mobile-app.clients.versola.test"),
        scala.util.Try(material.leaf.verify(ca.certificate.getPublicKey)).isSuccess,
        material.leaf.getIssuerX500Principal == ca.certificate.getSubjectX500Principal,
        material.leaf.getBasicConstraints == -1,
        material.leaf.getExtendedKeyUsage.contains("1.3.6.1.5.5.7.3.2"),
      )
    },
    test("keeps a full distinguished name") {
      val dn = "CN=mobile-app,OU=tenant-a,O=Versola"
      for material <- issued(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, dn))
      yield assertTrue(material.subjectValues("subject_dn") == Set(dn))
    },
    // An edge enrolling with a token alone cannot name itself in its request: central names the certificate, and the request is the
    // proof of the key.
    test("when central names the certificate, the request's own subject and names are not used") {
      val named = EdgeIdentityNames.subject(
        edgeId = versola.central.configuration.edges.EdgeId("edge-a"),
        groupId = versola.central.configuration.edges.EdgeGroupId("acme"),
      )
      val anonymous = CertificateSubject("CN=enrolling", "enrolling", Nil, Nil, Nil, Nil)
      for
        issuer <- issuer
        generated <- CertificateSubject.generate(anonymous)
        request = ClientCertificateRequests.signingRequest(generated.csrPem, named, 14.days, namedByCentral = true)
        leaf <- issuer.sign(request)
        material <- ZIO.fromEither(PrivateClientCertificate(leaf + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
        asRequested <- issuer.sign(ClientCertificateRequests.signingRequest(generated.csrPem, named, 14.days))
        plain <- ZIO.fromEither(PrivateClientCertificate(asRequested + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
      yield assertTrue(
        material.leaf.getSubjectX500Principal == named.principal,
        material.subjectValues("san_uri") == Set("urn:versola:edge:edge-a", "urn:versola:edge-group:acme"),
        // signed as ever when the request is the authority: the anonymous request's names, none
        plain.subjectValues("san_uri").isEmpty,
      )
    },
    // The test CA is valid for one day, so a 10-day request is cut to what the CA itself outlives.
    test("is never valid past the CA's own expiry") {
      for material <- issued(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "a.test"), 10.days)
      yield assertTrue(material.leaf.getNotAfter.toInstant == ca.certificate.getNotAfter.toInstant)
    },
    // A comma inside a value is escaped in the RFC 2253 form and plain in the request, and a CA
    // compares the request's -- so the subject handed to it must be the decoded one.
    test("the common name of a DN with an escaped comma is the decoded one") {
      val dn = """CN=app\,blue,O=Versola"""
      val subject = ClientCertificateRequests.subjectFor(ClientId("x"), MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, dn))
      assertTrue(subject.map(_.commonName) == Right("app,blue"))
    },
    test("the default subject is CN=<client>,OU=<tenant>,O=Versola") {
      val auth = ClientCertificateRequests.defaultAuth(TenantId("tenant-a"), ClientId("mobile-app"))
      assertTrue(auth.subjectType == MutualTlsSubjectType.subject_dn, auth.subjectValue == "CN=mobile-app,OU=tenant-a,O=Versola")
    },
  ) @@ TestAspect.withLiveClock
