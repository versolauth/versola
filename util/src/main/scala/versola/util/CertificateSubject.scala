package versola.util

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.{Extension, ExtensionsGenerator, GeneralName, GeneralNames}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.{JcaContentSignerBuilder, JcaContentVerifierProviderBuilder}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.{JcaPKCS10CertificationRequest, JcaPKCS10CertificationRequestBuilder}
import zio.*
import zio.json.JsonCodec
import zio.schema.*

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.security.auth.x500.X500Principal
import scala.jdk.CollectionConverters.*

/** What an edge-fronted client's certificate must say: the subject and the alternative names
  * auth later compares a presented certificate against (RFC 8705 §2.1.2).
  *
  * Plain data so that central, which decides it from the client's `mtlsAuth`, and the edge that
  * generates the key and the request can agree on it without sharing a registration model:
  * central hands it to the edge, the edge builds a request for exactly it, and central checks the
  * request it gets back says exactly that ([[CertificateSubject.matches]]).
  *
  * @param distinguishedName the subject in the RFC 4514 form `X500Principal.getName(RFC2253)` gives.
  * @param commonName the decoded CN, which a CA's one-time token names.
  */
case class CertificateSubject(
    distinguishedName: String,
    commonName: String,
    dnsNames: List[String] = Nil,
    uris: List[String] = Nil,
    emailAddresses: List[String] = Nil,
    ipAddresses: List[String] = Nil,
) derives JsonCodec, Schema:

  def principal: X500Principal = X500Principal(distinguishedName)

  private def alternativeNames: List[GeneralName] =
    dnsNames.map(GeneralName(GeneralName.dNSName, _)) ++
      uris.map(GeneralName(GeneralName.uniformResourceIdentifier, _)) ++
      emailAddresses.map(GeneralName(GeneralName.rfc822Name, _)) ++
      ipAddresses.map(GeneralName(GeneralName.iPAddress, _))

object CertificateSubject:

  /** A fresh EC P-256 key pair and the PKCS#10 request for [[CertificateSubject]] it signs. */
  case class Generated(privateKeyPem: String, csrPem: String)

  def generate(subject: CertificateSubject): Task[Generated] =
    ZIO.attemptBlocking:
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"))
      val pair = generator.generateKeyPair()
      val builder = JcaPKCS10CertificationRequestBuilder(subject.principal, pair.getPublic)
      val names = subject.alternativeNames
      if names.nonEmpty then
        val extensions = ExtensionsGenerator()
        extensions.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toArray))
        builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
      val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate)
      Generated(pem("PRIVATE KEY", pair.getPrivate.getEncoded), pem("CERTIFICATE REQUEST", builder.build(signer).getEncoded))

  /** Whether `csrPem` is a request signed by the key it names, for exactly `subject` -- no other
    * subject, no name added or missing. What an issuer must check before signing a request made by
    * someone other than itself: a CA signs what the request says, so what it says is what the
    * client is recognised by. */
  def matches(csrPem: String, subject: CertificateSubject): Either[String, Unit] =
    scala.util.Try:
      val csr = JcaPKCS10CertificationRequest(
        PEMParser(StringReader(csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest],
      )
      val signedByItsKey = csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(csr.getPublicKey))
      val sameSubject = X500Principal(csr.getSubject.getEncoded) == subject.principal
      val requested = Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName)))
        .map(extension => GeneralNames.getInstance(extension.getParsedValue).getNames.toList)
        .getOrElse(Nil)
      def names(tag: Int): Set[String] = requested.filter(_.getTagNo == tag).map(name => GeneralName.getInstance(name).getName.toString).toSet
      def sans(dns: Set[String], uris: Set[String], mails: Set[String], ips: Set[String]) =
        names(GeneralName.dNSName) == dns && names(GeneralName.uniformResourceIdentifier) == uris &&
          names(GeneralName.rfc822Name) == mails && names(GeneralName.iPAddress) == ips
      (signedByItsKey, sameSubject, sans(subject.dnsNames.toSet, subject.uris.toSet, subject.emailAddresses.toSet, subject.ipAddresses.toSet))
    .toEither.left.map(error => s"the request is not a readable PKCS#10: ${error.getMessage}").flatMap:
      case (false, _, _) => Left("the request is not signed by the key it names")
      case (_, false, _) => Left("the request's subject is not the one the client is registered by")
      case (_, _, false) => Left("the request's alternative names are not the ones the client is registered by")
      case _ => Right(())

  /** The decoded common name of a subject -- `app,blue` for `CN=app\,blue`, which is what the
    * request carries and what a CA compares a token's subject against. */
  def commonNameOf(principal: X500Principal): Option[String] =
    X500Name.getInstance(principal.getEncoded).getRDNs(BCStyle.CN).headOption
      .map(rdn => rdn.getFirst.getValue.asInstanceOf[org.bouncycastle.asn1.ASN1String].getString)

  def pem(label: String, der: Array[Byte]): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
    s"-----BEGIN $label-----\n$body\n-----END $label-----\n"
