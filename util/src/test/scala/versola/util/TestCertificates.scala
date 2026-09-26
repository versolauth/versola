package versola.util

import org.bouncycastle.asn1.x509.{BasicConstraints, Extension, GeneralName, GeneralNames}
import org.bouncycastle.x509.X509V3CertificateGenerator

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator, PrivateKey}
import java.util.Date
import javax.security.auth.x500.X500Principal
import zio.json.*
import zio.json.ast.Json

/** Client certificates for the specs that exercise RFC 8705 from the client's side, where the
  * private key is the point — `versola.oauth.mtls.ClientCertificate`'s fixtures are fixed PEM
  * because auth never holds one.
  *
  * Generated per run rather than checked in, so that no private key lives in the repository.
  */
object TestCertificates:

  /** A certificate and the key that signed it, in the PEM form a client is provisioned with:
    * the certificate first, then an unencrypted PKCS#8 key, which is the only pair a TLS stack
    * here reads back. */
  case class Generated(certificate: X509Certificate, privateKey: PrivateKey):
    def certificatePem: String = pem("CERTIFICATE", certificate.getEncoded)

    def privateKeyPem: String = pem("PRIVATE KEY", privateKey.getEncoded)

    /** Both halves as one document, which is how a client certificate is registered. */
    def bundle: String = s"$certificatePem\n$privateKeyPem"

    /** RFC 4514 subject, rendered as auth renders the one it compares against. */
    def subjectDn: String = certificate.getSubjectX500Principal.getName(X500Principal.RFC2253)

    /** The certificate's own public key as an RFC 7517 key set, which is what a client
      * registers for RFC 8705 §2.2. */
    def jwks: JsonWebKeySet =
      val key = JsonDocument(
        com.nimbusds.jose.jwk.JWK.parse(certificate).nn.toPublicJWK.nn.toJSONString.nn,
      )
      JsonWebKeySet(Json.Obj("keys" -> Json.Arr(key)))

  /** @param subject the RFC 4514 subject to issue to.
    * @param dnsName a `dNSName` alternative name, which `san_dns` registers against.
    * @param algorithm `RSA` or `EC`; both are accepted wherever a certificate is read, and a
    *                  spec that only ever generated one would not notice a path that assumes
    *                  it.
    * @param ca marks the certificate a certificate authority (`BasicConstraints`, critical) --
    *           for the one spec asserting something refuses to trust one, not for a client's
    *           own certificate, which is always an end entity.
    */
  def generate(
      subject: String = "CN=edge-mtls-client,O=Versola,C=KZ",
      dnsName: Option[String] = None,
      algorithm: String = "RSA",
      ca: Boolean = false,
  ): Generated =
    val keyPair = keys(algorithm)
    val generator = X509V3CertificateGenerator()
    val principal = X500Principal(subject)
    generator.setSerialNumber(BigInteger.valueOf(System.nanoTime()))
    generator.setIssuerDN(principal)
    generator.setSubjectDN(principal)
    generator.setNotBefore(Date(System.currentTimeMillis() - 3600_000))
    generator.setNotAfter(Date(System.currentTimeMillis() + 86_400_000))
    generator.setPublicKey(keyPair.getPublic.nn)
    generator.setSignatureAlgorithm(if algorithm == "EC" then "SHA256withECDSA" else "SHA256withRSA")
    dnsName.foreach: name =>
      generator.addExtension(
        Extension.subjectAlternativeName.nn,
        false,
        GeneralNames(GeneralName(GeneralName.dNSName, name)),
      )
    if ca then generator.addExtension(Extension.basicConstraints.nn, true, BasicConstraints(true))
    Generated(generator.generate(keyPair.getPrivate.nn).nn, keyPair.getPrivate.nn)

  private def keys(algorithm: String): KeyPair =
    val generator = KeyPairGenerator.getInstance(algorithm).nn
    if algorithm == "EC" then generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    else generator.initialize(2048)
    generator.generateKeyPair().nn

  private def JsonDocument(json: String): Json.Obj =
    json.fromJson[Json.Obj].toOption.get

  private def pem(label: String, der: Array[Byte]): String =
    val body = java.util.Base64.getMimeEncoder(64, "\n".getBytes("US-ASCII")).nn.encodeToString(der)
    s"-----BEGIN $label-----\n$body\n-----END $label-----"
