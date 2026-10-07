package versola.central.configuration.clients.certificates

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.{Extension, ExtensionsGenerator, GeneralName, GeneralNames}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import versola.central.configuration.clients.{ClientId, MutualTlsAuth, MutualTlsSubjectType}
import versola.central.configuration.tenants.TenantId
import zio.*

import java.nio.charset.StandardCharsets
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator}
import java.util.Base64
import javax.security.auth.x500.X500Principal

/** Builds what an edge-fronted client's certificate must say, and the key pair and PKCS#10
  * request that ask a CA for it.
  *
  * What it must say is decided by the client's own `mtlsAuth` registration, which is what auth
  * later compares the presented certificate against (RFC 8705 §2.1.2): the registered subject
  * value has to appear in the certificate, so the request is built from it and not the other way
  * round. Registration (`InvalidRegistrationConfiguration.validateEdgeClientCertificate`) holds
  * a certificate to the same rule and refuses one that misses it.
  */
object ClientCertificateRequests:

  /** The key pair is EC P-256: small, and what a TLS stack and `PrivateClientCertificate` both
    * read. */
  case class Generated(privateKeyPem: String, request: CertificateSigningRequest)

  case class Subject(
      principal: X500Principal,
      commonName: String,
      dnsNames: List[String] = Nil,
      uris: List[String] = Nil,
      emailAddresses: List[String] = Nil,
      ipAddresses: List[String] = Nil,
  )

  /** What a registration that names no `mtlsAuth` is recognised by: `CN=<client>,OU=<tenant>,O=Versola`,
    * the subject #451's CA has always issued. Rendered the way auth renders a presented certificate's. */
  def defaultAuth(tenantId: TenantId, clientId: ClientId): MutualTlsAuth.TlsClientAuth =
    val dn = X500Principal(s"CN=${escape(clientId)},OU=${escape(tenantId)},O=Versola").getName(X500Principal.RFC2253)
    MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, dn)

  def subjectFor(clientId: ClientId, auth: MutualTlsAuth): Either[String, Subject] =
    auth match
      case MutualTlsAuth.SelfSignedTlsClientAuth() =>
        Left("self_signed_tls_client_auth has no subject to issue for -- its certificate is matched by key, not by name")
      case MutualTlsAuth.TlsClientAuth(subjectType, value) =>
        val own = X500Principal(s"CN=${escape(clientId)}")
        subjectType match
          case MutualTlsSubjectType.subject_dn =>
            scala.util.Try(X500Principal(value)).toEither.left
              .map(error => s"mtlsAuth subject_dn '$value' is not an RFC 4514 distinguished name: ${error.getMessage}")
              .map(principal => Subject(principal, commonNameOf(principal).getOrElse(clientId)))
          case MutualTlsSubjectType.san_dns => Right(Subject(own, clientId, dnsNames = List(value)))
          case MutualTlsSubjectType.san_uri => Right(Subject(own, clientId, uris = List(value)))
          case MutualTlsSubjectType.san_email => Right(Subject(own, clientId, emailAddresses = List(value)))
          case MutualTlsSubjectType.san_ip => Right(Subject(own, clientId, ipAddresses = List(value)))

  def generate(subject: Subject, validity: Duration): Task[Generated] =
    ZIO.attemptBlocking:
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"))
      val pair = generator.generateKeyPair()
      Generated(pem("PRIVATE KEY", pair.getPrivate.getEncoded), request(subject, pair, validity))

  private def request(subject: Subject, pair: KeyPair, validity: Duration): CertificateSigningRequest =
    val names =
      subject.dnsNames.map(GeneralName(GeneralName.dNSName, _)) ++
        subject.uris.map(GeneralName(GeneralName.uniformResourceIdentifier, _)) ++
        subject.emailAddresses.map(GeneralName(GeneralName.rfc822Name, _)) ++
        subject.ipAddresses.map(GeneralName(GeneralName.iPAddress, _))
    val builder = JcaPKCS10CertificationRequestBuilder(subject.principal, pair.getPublic)
    if names.nonEmpty then
      val extensions = ExtensionsGenerator()
      extensions.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toArray))
      builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate)
    CertificateSigningRequest(
      csrPem = pem("CERTIFICATE REQUEST", builder.build(signer).getEncoded),
      commonName = subject.commonName,
      dnsNames = subject.dnsNames,
      uris = subject.uris,
      emailAddresses = subject.emailAddresses,
      ipAddresses = subject.ipAddresses,
      validity = validity,
    )

  def pem(label: String, der: Array[Byte]): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
    s"-----BEGIN $label-----\n$body\n-----END $label-----\n"

  private def commonNameOf(principal: X500Principal): Option[String] =
    principal.getName(X500Principal.RFC2253).split("(?<!\\\\),").toList
      .find(_.startsWith("CN=")).map(_.stripPrefix("CN="))

  private def escape(value: String): String =
    value.flatMap:
      case c if ",+\"\\<>;=".contains(c) => s"\\$c"
      case c => c.toString
