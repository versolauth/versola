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
    test("text that is not a request is refused, not thrown") {
      assertTrue(CertificateSubject.matches("not a request", subject).isLeft)
    },
    test("the decoded common name has no escaping") {
      assertTrue(CertificateSubject.commonNameOf(X500Principal("""CN=app\,blue,O=Versola""")) == Some("app,blue"))
    },
  )
