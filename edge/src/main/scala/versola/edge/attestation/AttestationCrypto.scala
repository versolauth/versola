package versola.edge.attestation

import com.nimbusds.jose.jwk.{Curve, ECKey}
import org.bouncycastle.asn1.{ASN1Encodable, ASN1OctetString, ASN1Primitive, ASN1Sequence, ASN1TaggedObject}
import versola.edge.attestation.AttestationFailure.Kind

import java.io.ByteArrayInputStream
import java.security.cert.{CertPathValidator, CertificateFactory, PKIXParameters, TrustAnchor, X509Certificate}
import java.security.interfaces.ECPublicKey
import java.security.{MessageDigest, PublicKey}
import java.time.Instant
import java.util.Date
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** What the two platforms' verifiers share: reading what a client sent as certificates, deciding whether they chain to a root, and
  * reading the values the platforms put in X.509 extensions. Every method answers with an [[AttestationFailure]] rather than
  * throwing, since the input is whatever a client chose to send. */
private[attestation] object AttestationCrypto:

  /** Bounds on what is parsed, so that an attestation costs what a real one does and no more. */
  val MaxCertificateLength = 8 * 1024
  val MaxChainLength = 6

  def sha256(parts: Array[Byte]*): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    parts.foreach(digest.update)
    digest.digest()

  def equal(a: Array[Byte], b: Array[Byte]): Boolean = MessageDigest.isEqual(a, b)

  def malformed(detail: String): AttestationFailure = AttestationFailure(Kind.Malformed, detail)

  def certificates(der: List[Array[Byte]]): Either[AttestationFailure, List[X509Certificate]] =
    if der.isEmpty then Left(malformed("the certificate chain is empty"))
    else if der.sizeIs > MaxChainLength then Left(malformed(s"the certificate chain has more than $MaxChainLength certificates"))
    else if der.exists(_.length > MaxCertificateLength) then Left(malformed("a certificate is too large"))
    else
      val factory = CertificateFactory.getInstance("X.509")
      Try(der.map(bytes => factory.generateCertificate(ByteArrayInputStream(bytes)).asInstanceOf[X509Certificate])).toEither
        .left.map(_ => malformed("a certificate of the chain does not parse"))

  /** Whether `chain` (leaf first) leads to one of `roots`, as it stood at `now`. The root itself may or may not be the last
    * element: Android sends it, Apple does not, and a path is validated from the certificate below the anchor. A trailing
    * self-signed certificate is the root when it carries the key of one of `roots`, whatever else it says: a device sends the copies
    * of the root issued over the years (same key, other serial and validity), and a copy that has expired must not take the path
    * down with it. Revocation is not asked of the JDK -- it would fetch a CRL from an address the app chose -- and is the caller's,
    * by serial.
    *
    * `ignoreValidity` is for Android's factory-provisioned chains, whose attestation keys outlive their certificates (devices launched
    * before 2021 carry expired ones, which Google says to keep trusting): the validity periods are then required only to agree with
    * each other, that is to share a moment, and not to contain `now`. */
  def validate(chain: List[X509Certificate], roots: Set[X509Certificate], now: Instant, ignoreValidity: Boolean = false): Either[AttestationFailure, Unit] =
    val untrusted = AttestationFailure(Kind.UntrustedChain, "the certificate chain does not lead to the platform's root")
    val path = withoutRoot(chain, roots)
    if path.isEmpty || roots.isEmpty then Left(untrusted)
    else if ignoreValidity then
      val from = path.map(_.getNotBefore).maxBy(_.getTime)
      Either.cond(path.forall(!_.getNotAfter.before(from)) && linked(path, roots, from), (), untrusted)
    else
      Try:
        val factory = CertificateFactory.getInstance("X.509")
        val parameters = PKIXParameters(roots.map(TrustAnchor(_, null)).asJava)
        parameters.setRevocationEnabled(false)
        parameters.setDate(Date.from(now))
        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(path.asJava), parameters)
      .toEither.left.map(_ => untrusted).map(_ => ())

  /** Whether each certificate of `path` was issued by the next one, by name and by signature, the last by one of `roots`, and all were
    * valid at `at`. What the JDK's path validation adds is left out on purpose: the certificates of the older factory-provisioned chains
    * (Pixel 2, 2017) are intermediates that are not marked as authorities and carry no `keyCertSign`, which the JDK refuses and which
    * real devices send. Nothing here lets a key sign that did not: a certificate that does not chain by signature to the root is not
    * accepted, and the one place an attacker's key could be inserted -- below the attested key -- is closed by the position of the key
    * description (`AndroidKeyAttestation.extensionsInPlace`). */
  private def linked(path: List[X509Certificate], roots: Set[X509Certificate], at: Date): Boolean =
    def signedBy(child: X509Certificate, parent: X509Certificate): Boolean =
      child.getIssuerX500Principal == parent.getSubjectX500Principal && Try(child.verify(parent.getPublicKey)).isSuccess
    path.forall(certificate => Try(certificate.checkValidity(at)).isSuccess) &&
      path.zip(path.tail).forall(signedBy) &&
      roots.exists(root => signedBy(path.last, root))

  /** `chain` without its last certificate when that is a self-signed copy of one of `roots`. */
  def withoutRoot(chain: List[X509Certificate], roots: Set[X509Certificate]): List[X509Certificate] =
    chain.lastOption match
      case Some(last) if last.getSubjectX500Principal == last.getIssuerX500Principal && roots.exists(_.getPublicKey == last.getPublicKey) =>
        chain.init
      case _ => chain

  /** The root of `roots` that `chain` is issued under, by name; the path validation is what proves it. */
  def anchorOf(chain: List[X509Certificate], roots: Set[X509Certificate]): Option[X509Certificate] =
    withoutRoot(chain, roots).lastOption.flatMap(last => roots.find(_.getSubjectX500Principal == last.getIssuerX500Principal))

  /** The value of an extension of the certificate, as the DER of what it holds. */
  def extensionValue(certificate: X509Certificate, oid: String): Option[Array[Byte]] =
    Option(certificate.getExtensionValue(oid)).flatMap: wrapped =>
      Try(ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(wrapped)).getOctets).toOption

  def sequence(der: Array[Byte]): Option[ASN1Sequence] =
    Try(ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))).toOption

  /** The member of an `AuthorizationList` with the given explicit tag. */
  def tagged(list: ASN1Sequence, tag: Int): Option[ASN1Encodable] =
    list.iterator.asScala.collectFirst:
      case object_ : ASN1TaggedObject if object_.hasContextTag(tag) => object_.getExplicitBaseObject

  /** The RFC 7638 thumbprint of a P-256 key: what `Dpop.verify` puts in a proof's `jkt`, so that a key can be compared with one
    * a certificate carries. */
  def thumbprint(key: PublicKey): Option[String] = key match
    case ec: ECPublicKey =>
      Try(ECKey.Builder(Curve.P_256, ec).build().computeThumbprint().toString).toOption
    case _ => None
