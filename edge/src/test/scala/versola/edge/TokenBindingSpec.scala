package versola.edge

import versola.edge.model.Confirmation
import versola.util.TestCertificates
import zio.test.*

object TokenBindingSpec extends ZIOSpecDefault:

  private val certificate = TestCertificates.generate().certificate
  private val thumbprint = TokenBinding.thumbprint(certificate)
  private val over = Presentation.BearerOverMutualTls(thumbprint)
  private val bound = Some(Confirmation(certificateThumbprint = Some(thumbprint), jkt = None))

  def spec = suite("TokenBinding.check")(
    test("the thumbprint is the base64url SHA-256 of the DER certificate, unpadded (RFC 8705 §3.1)") {
      assertTrue(
        thumbprint.length == 43,
        thumbprint == java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
          java.security.MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded),
        ),
      )
    },
    test("a certificate-bound token is accepted over the certificate it names") {
      assertTrue(TokenBinding.check(bound, over) == BindingCheck.AcceptCallerCertificate)
    },
    test("a certificate-bound token is refused over another certificate") {
      val other = TokenBinding.thumbprint(TestCertificates.generate().certificate)
      assertTrue(TokenBinding.check(bound, Presentation.BearerOverMutualTls(other)) == BindingCheck.CertificateOutsideSession)
    },
    test("a certificate-bound token stays refused wherever no certificate was presented") {
      assertTrue(
        TokenBinding.check(bound, Presentation.Bearer) == BindingCheck.CertificateOutsideSession,
        TokenBinding.check(bound, Presentation.Dpop) == BindingCheck.CertificateOutsideSession,
        TokenBinding.check(bound, Presentation.Cookie) == BindingCheck.Accept,
      )
    },
    test("a key-bound token is not helped by a certificate: a Bearer presentation is a downgrade") {
      val key = Some(Confirmation(jkt = Some("jkt-1")))
      assertTrue(TokenBinding.check(key, over) == BindingCheck.Downgrade)
    },
    test("a token bound to both is refused over the certificate it names") {
      val both = Some(Confirmation(jkt = Some("jkt-1"), certificateThumbprint = Some(thumbprint)))
      assertTrue(TokenBinding.check(both, over) == BindingCheck.Downgrade)
    },
    test("an unbound token is accepted as a bearer token") {
      assertTrue(TokenBinding.check(None, over) == BindingCheck.Accept)
    },
  )
