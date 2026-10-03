package versola.central.configuration.clients

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.{X500Name, X500NameBuilder}
import org.bouncycastle.asn1.x509.{AlgorithmIdentifier, AuthorityKeyIdentifier, BasicConstraints, Certificate, ExtendedKeyUsage, Extension, ExtensionsGenerator, KeyPurposeId, KeyUsage, SubjectKeyIdentifier, SubjectPublicKeyInfo, Time, V3TBSCertificateGenerator}
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.asn1.{ASN1Encodable, ASN1Encoding, ASN1Integer, ASN1OctetString, DERBitString, DERNull, DERSequence}
import versola.central.CentralConfig
import versola.central.configuration.tenants.TenantId
import versola.util.PrivateClientCertificate
import zio.*

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.{ECGenParameterSpec, PKCS8EncodedKeySpec}
import java.security.{KeyFactory, KeyPairGenerator, PrivateKey, SecureRandom, Signature}
import java.time.Instant
import java.util.{Base64, Date}
import scala.util.Try

/** Issues the certificate an edge presents as an edge-fronted client (RFC 8705 §2.1
  * `tls_client_auth`), so registering one does not leave obtaining it to the operator (#440).
  *
  * The certificate is issued directly by the configured CA: auth's mutual-TLS listener is handed
  * the leaf only, so there is no intermediate it could build a chain through. The subject is
  * `O=Versola, OU=<tenant>, CN=<client>`, which is what the client is then registered by
  * (`subject_dn`); the key is a P-256 key generated here, and like the rest of the certificate
  * reaches no one but the edge fronting the client.
  */
trait ClientCertificateAuthority:

  /** `None` when central has no CA configured. */
  def issue(tenantId: TenantId, clientId: ClientId): Task[Option[ClientCertificateAuthority.Issued]]

object ClientCertificateAuthority:

  /** @param certificate the certificate and its private key, as `edgeClientCertificate` holds them
    * @param subjectDn its subject in the RFC 4514 form a `subject_dn` registration is compared by
    */
  case class Issued(certificate: PrivateClientCertificate, subjectDn: String)

  val DefaultValidityDays = 365

  /** Starts no earlier than this before issuance, so a party whose clock runs behind central's
    * does not refuse a certificate as not yet valid. */
  private val ClockSkew = 5.minutes

  val live: ZLayer[CentralConfig, Throwable, ClientCertificateAuthority] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig]: config =>
        config.clientCertificateAuthority match
          case None => ZIO.succeed(Unconfigured)
          case Some(authority) => load(authority)

  /** Read once, at startup, so a CA that cannot issue stops central from starting rather than
    * failing the first registration that asks for a certificate. */
  def load(config: CentralConfig.ClientCertificateAuthorityConfig): Task[ClientCertificateAuthority] =
    for
      certificatePem <- ZIO.attemptBlocking(Files.readString(Path.of(config.certificate)))
      privateKeyPem <- ZIO.attemptBlocking(Files.readString(Path.of(config.privateKey)))
      now <- Clock.instant
      authority <- ZIO.fromEither(
        fromPem(certificatePem, privateKeyPem, config.validityDays.getOrElse(DefaultValidityDays), now),
      ).mapError(reason => IllegalArgumentException(s"client-certificate-authority: $reason"))
    yield authority

  def fromPem(
      certificatePem: String,
      privateKeyPem: String,
      validityDays: Int,
      now: Instant,
  ): Either[String, ClientCertificateAuthority] =
    for
      _ <- Either.cond(validityDays > 0, (), s"validity-days must be positive, was $validityDays")
      certificate <- readCertificate(certificatePem)
      _ <- Either.cond(
        certificate.getBasicConstraints >= 0,
        (),
        "certificate is not a certificate authority (no BasicConstraints CA:TRUE)",
      )
      _ <- Either.cond(
        certificate.getNotAfter.toInstant.isAfter(now),
        (),
        s"certificate expired at ${certificate.getNotAfter.toInstant}",
      )
      _ <- PrivateClientCertificate.validate(s"${certificatePem.trim}\n${privateKeyPem.trim}\n")
        .left.map(reason => s"certificate and private key: $reason")
      privateKey <- readPrivateKey(privateKeyPem)
    yield Configured(certificate, privateKey, validityDays)

  object Unconfigured extends ClientCertificateAuthority:
    override def issue(tenantId: TenantId, clientId: ClientId): Task[Option[Issued]] = ZIO.none

  private final class Configured(
      caCertificate: X509Certificate,
      caKey: PrivateKey,
      validityDays: Int,
  ) extends ClientCertificateAuthority:
    private val random = SecureRandom()

    private val (signatureAlgorithm, signatureName) =
      if caKey.getAlgorithm == "EC" then
        (AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256), "SHA256withECDSA")
      else (AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE), "SHA256withRSA")

    private val authorityKeyIdentifier: Option[AuthorityKeyIdentifier] =
      Option(caCertificate.getExtensionValue(Extension.subjectKeyIdentifier.getId)).map: value =>
        val identifier = SubjectKeyIdentifier.getInstance(ASN1OctetString.getInstance(value).getOctets)
        AuthorityKeyIdentifier(identifier.getKeyIdentifier)

    override def issue(tenantId: TenantId, clientId: ClientId): Task[Option[Issued]] =
      Clock.instant.flatMap: now =>
        ZIO.attemptBlocking(issueAt(tenantId, clientId, now)).flatMap(ZIO.fromEither(_).mapError(IllegalStateException(_)))
          .asSome

    private def issueAt(tenantId: TenantId, clientId: ClientId, now: Instant): Either[String, Issued] =
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"), random)
      val keyPair = generator.generateKeyPair()

      val subject = X500NameBuilder(BCStyle.INSTANCE)
        .addRDN(BCStyle.O, "Versola")
        .addRDN(BCStyle.OU, tenantId.toString)
        .addRDN(BCStyle.CN, clientId.toString)
        .build()
      val requestedEnd = now.plus(java.time.Duration.ofDays(validityDays.toLong))
      val caEnd = caCertificate.getNotAfter.toInstant
      val notAfter = if requestedEnd.isAfter(caEnd) then caEnd else requestedEnd

      val extensions = ExtensionsGenerator()
      extensions.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
      extensions.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
      extensions.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
      authorityKeyIdentifier.foreach(extensions.addExtension(Extension.authorityKeyIdentifier, false, _))

      val tbs = V3TBSCertificateGenerator()
      // RFC 5280 §4.1.2.2: positive, at most 20 octets, and unpredictable.
      tbs.setSerialNumber(ASN1Integer(BigInteger(159, random).setBit(158)))
      tbs.setSignature(signatureAlgorithm)
      tbs.setIssuer(X500Name.getInstance(caCertificate.getSubjectX500Principal.getEncoded))
      tbs.setSubject(subject)
      tbs.setStartDate(Time(Date.from(now.minus(ClockSkew))))
      tbs.setEndDate(Time(Date.from(notAfter)))
      tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.getPublic.getEncoded))
      tbs.setExtensions(extensions.generate())
      val tbsCertificate = tbs.generateTBSCertificate()

      val signer = Signature.getInstance(signatureName)
      signer.initSign(caKey)
      signer.update(tbsCertificate.getEncoded(ASN1Encoding.DER))
      val certificate = Certificate.getInstance(
        DERSequence(Array[ASN1Encodable](tbsCertificate, signatureAlgorithm, DERBitString(signer.sign()))),
      )

      val pem = s"${toPem("CERTIFICATE", certificate.getEncoded(ASN1Encoding.DER))}\n" +
        s"${toPem("PRIVATE KEY", keyPair.getPrivate.getEncoded)}\n"
      for
        issued <- PrivateClientCertificate.validate(pem)
        material <- issued.material
      yield Issued(issued, material.subjectDn)

  private def blocks(pem: String, label: String): List[Array[Byte]] =
    s"(?s)-----BEGIN $label-----(.*?)-----END $label-----".r
      .findAllMatchIn(pem)
      .flatMap(m => Try(Base64.getMimeDecoder.decode(m.group(1))).toOption)
      .toList

  private def readCertificate(pem: String): Either[String, X509Certificate] =
    blocks(pem, "CERTIFICATE") match
      case der :: Nil =>
        Try(CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)))
          .toEither.left.map(error => s"certificate is not a valid X.509 certificate: ${error.getMessage}")
          .flatMap:
            case x509: X509Certificate => Right(x509)
            case other => Left(s"certificate is not an X.509 certificate: ${other.getType}")
      case Nil => Left("certificate must contain a CERTIFICATE block")
      case _ => Left("certificate must contain exactly one CERTIFICATE block, the CA's own")

  private def readPrivateKey(pem: String): Either[String, PrivateKey] =
    blocks(pem, "PRIVATE KEY") match
      case der :: Nil =>
        val spec = PKCS8EncodedKeySpec(der)
        List("RSA", "EC")
          .flatMap(algorithm => Try(KeyFactory.getInstance(algorithm).generatePrivate(spec)).toOption)
          .headOption
          .toRight("private-key is neither an RSA nor an EC key in PKCS#8 form")
      case _ => Left("private-key must contain exactly one unencrypted PKCS#8 PRIVATE KEY block")

  private def toPem(label: String, der: Array[Byte]): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes("US-ASCII")).encodeToString(der)
    s"-----BEGIN $label-----\n$body\n-----END $label-----"
