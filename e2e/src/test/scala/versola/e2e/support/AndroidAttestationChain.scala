package versola.e2e.support

import org.bouncycastle.asn1.x509.{BasicConstraints, Extension}
import org.bouncycastle.asn1.{
  ASN1Boolean,
  ASN1Encodable,
  ASN1Enumerated,
  ASN1Integer,
  DERSequence,
  DERSet,
  DEROctetString,
  DERTaggedObject,
}
import org.bouncycastle.x509.X509V3CertificateGenerator

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, MessageDigest, PrivateKey, PublicKey}
import java.util.{Base64, Date}
import javax.security.auth.x500.X500Principal

/** An Android Key Attestation chain of a test authority, for a public key that is not in a Keystore: what edge reads on `/native/start`,
  * built to the schema in Android's "Verify hardware-backed key pairs with Key Attestation", so that the path a verification that
  * succeeds takes through the whole stack can be driven. The authority is the test's own: edge is started trusting it
  * (`native.attestation.android-roots`), since a chain to Google's root can only come from a device.
  *
  * It is what the unit suites' fixtures make too, written again here because e2e does not share their classes, and because what this
  * is held to is edge's reading of the format at the other end of the wire.
  */
final class AndroidAttestationChain private (rootPem: String, private val root: AndroidAttestationChain.Authority, private val intermediate: AndroidAttestationChain.Authority):

  /** The root, as the PEM file `android-roots` names. */
  def rootCertificatePem: String = rootPem

  /** The chain for `key`, leaf first and the root last, as DER: a key generated in the secure hardware (not imported) of a locked, verified
    * device, for the app `packageName` signed with the certificate whose SHA-256 is `signingDigest`, with the attestation challenge
    * `challenge`. */
  def chainFor(key: PublicKey, challenge: Array[Byte], packageName: String, signingDigest: Array[Byte]): List[Array[Byte]] =
    val leaf = AndroidAttestationChain.issue(
      subject = "CN=Android Keystore Key",
      publicKey = key,
      issuer = Some(intermediate),
      signer = intermediate.key,
      ca = false,
      serial = BigInteger.valueOf(System.nanoTime()),
      extensions = List(AndroidAttestationChain.KeyDescriptionOid -> AndroidAttestationChain.keyDescription(challenge, packageName, signingDigest)),
    )
    List(leaf, intermediate.certificate, root.certificate).map(_.getEncoded)

object AndroidAttestationChain:

  private[support] final case class Authority(certificate: X509Certificate, key: PrivateKey)

  private val KeyDescriptionOid = "1.3.6.1.4.1.11129.2.1.17"

  def sha256(value: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(value)

  def make: AndroidAttestationChain =
    val rootAuthority = authority("CN=E2E Key Attestation Root,O=Test", None)
    val intermediateAuthority = authority("CN=E2E Key Attestation CA,O=Test", Some(rootAuthority))
    val pem = "-----BEGIN CERTIFICATE-----\n" +
      Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(rootAuthority.certificate.getEncoded) +
      "\n-----END CERTIFICATE-----\n"
    AndroidAttestationChain(pem, rootAuthority, intermediateAuthority)

  private def authority(subject: String, issuer: Option[Authority]): Authority =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val pair: KeyPair = generator.generateKeyPair()
    val certificate = issue(subject, pair.getPublic, issuer, issuer.fold(pair.getPrivate)(_.key), ca = true, BigInteger.valueOf(System.nanoTime()), Nil)
    Authority(certificate, pair.getPrivate)

  private def issue(
      subject: String,
      publicKey: PublicKey,
      issuer: Option[Authority],
      signer: PrivateKey,
      ca: Boolean,
      serial: BigInteger,
      extensions: List[(String, ASN1Encodable)],
  ): X509Certificate =
    val generator = X509V3CertificateGenerator()
    val principal = X500Principal(subject)
    generator.setSerialNumber(serial)
    generator.setIssuerDN(issuer.fold(principal)(_.certificate.getSubjectX500Principal))
    generator.setSubjectDN(principal)
    generator.setNotBefore(Date(System.currentTimeMillis() - 3_600_000))
    generator.setNotAfter(Date(System.currentTimeMillis() + 3 * 86_400_000L))
    generator.setPublicKey(publicKey)
    generator.setSignatureAlgorithm("SHA256withECDSA")
    if ca then generator.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    extensions.foreach((oid, value) => generator.addExtension(oid, false, value))
    generator.generate(signer)

  /** The `KeyDescription`: attestation and KeyMint security level 1 (the trusted environment), the challenge, and the authorization lists --
    * the app's package and signing certificate in the software-enforced one, and in the hardware-enforced one the key's origin
    * (`GENERATED`) and the root of trust (locked bootloader, verified boot). */
  private def keyDescription(challenge: Array[Byte], packageName: String, signingDigest: Array[Byte]): DERSequence =
    val applicationId = DERSequence(
      Array[ASN1Encodable](
        DERSet(Array[ASN1Encodable](DERSequence(Array[ASN1Encodable](DEROctetString(packageName.getBytes(StandardCharsets.UTF_8)), ASN1Integer(1))))),
        DERSet(Array[ASN1Encodable](DEROctetString(signingDigest))),
      ),
    )
    val rootOfTrust = DERSequence(
      Array[ASN1Encodable](
        DEROctetString(Array.fill[Byte](32)(3)),
        ASN1Boolean.getInstance(true),
        ASN1Enumerated(0),
        DEROctetString(Array.fill[Byte](32)(4)),
      ),
    )
    val software = DERSequence(Array[ASN1Encodable](DERTaggedObject(true, 709, DEROctetString(applicationId.getEncoded))))
    val hardware = DERSequence(Array[ASN1Encodable](DERTaggedObject(true, 702, ASN1Integer(0)), DERTaggedObject(true, 704, rootOfTrust)))
    DERSequence(
      Array[ASN1Encodable](
        ASN1Integer(4),
        ASN1Enumerated(1),
        ASN1Integer(100),
        ASN1Enumerated(1),
        DEROctetString(challenge),
        DEROctetString(Array.emptyByteArray),
        software,
        hardware,
      ),
    )
