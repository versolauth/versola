package versola.oauth.est

import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import versola.util.{CertificateSubject, TestCertificates}
import zio.test.*

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.security.auth.x500.X500Principal

object EstCodecSpec extends ZIOSpecDefault:

  private def csrDer: Array[Byte] =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val keys = generator.generateKeyPair()
    JcaPKCS10CertificationRequestBuilder(X500Principal("CN=bank-a"), keys.getPublic)
      .build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate)).getEncoded

  def spec = suite("EstCodec")(
    suite("requestPem")(
      test("reads the base64 DER of RFC 7030 §4.2.1, with the line breaks a client wraps it in") {
        val wrapped = Base64.getMimeEncoder(64, "\r\n".getBytes).encodeToString(csrDer)
        val pem = EstCodec.requestPem(wrapped)
        assertTrue(pem.exists(_.startsWith("-----BEGIN CERTIFICATE REQUEST-----")))
      },
      test("tolerates the PEM armour some clients leave on") {
        val der = csrDer
        val armoured = CertificateSubject.pem("CERTIFICATE REQUEST", der)
        assertTrue(EstCodec.requestPem(armoured) == EstCodec.requestPem(Base64.getEncoder.encodeToString(der)))
      },
      test("refuses a request that asks for channel binding with challengePassword, and says why") {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val der = JcaPKCS10CertificationRequestBuilder(X500Principal("CN=bank-a"), keys.getPublic)
          .addAttribute(
            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.pkcs_9_at_challengePassword,
            org.bouncycastle.asn1.DERPrintableString("tls-unique-value"),
          )
          .build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate)).getEncoded
        val refused = EstCodec.requestPem(Base64.getEncoder.encodeToString(der))
        assertTrue(refused.left.exists(_.contains("challengePassword")))
      },
      test("refuses what is not base64, what is empty, and what is base64 of something that is not a PKCS#10") {
        assertTrue(
          EstCodec.requestPem("***not base64***").isLeft,
          EstCodec.requestPem("").isLeft,
          EstCodec.requestPem(Base64.getEncoder.encodeToString("hello".getBytes)).isLeft,
        )
      },
    ),
    suite("certsOnly")(
      test("is a PKCS#7 with the certificates and no signer, in base64 lines of 64 characters") {
        val a = TestCertificates.generate(subject = "CN=a")
        val b = TestCertificates.generate(subject = "CN=b")
        val encoded = EstCodec.certsOnly(List(a.certificate, b.certificate))
        val text = String(encoded, "US-ASCII")
        val parsed = CMSSignedData(Base64.getMimeDecoder.decode(text))
        val subjects = parsed.getCertificates.getMatches(null).toArray.toList
          .map(_.asInstanceOf[org.bouncycastle.cert.X509CertificateHolder].getSubject.toString)
        assertTrue(
          parsed.getSignerInfos.size == 0,
          subjects.toSet == Set("CN=a", "CN=b"),
          text.linesIterator.forall(_.length <= 64),
        )
      },
    ),
    suite("certificates")(
      test("reads a PEM chain in order") {
        val a = TestCertificates.generate(subject = "CN=a")
        val b = TestCertificates.generate(subject = "CN=b")
        val chain = EstCodec.certificates(a.certificatePem + "\n" + b.certificatePem).get
        assertTrue(chain.map(_.getSubjectX500Principal.getName) == List("CN=a", "CN=b"))
      },
    ),
  )
