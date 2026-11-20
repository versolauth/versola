package versola.edge.attestation

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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, MessageDigest, PrivateKey, Signature}
import java.util.Date
import javax.security.auth.x500.X500Principal

/** What the attestation specs make a platform's evidence out of. The shapes are those the platforms document, built here
  * independently of the code under test (the key id from the encoded key, the nonce and the signature by their own hashing), so that
  * a verifier is held to the documented format and not to its own reading of it. They are not what a device produces: no spec here
  * proves interoperability with Apple's or Google's own output. */
object AttestationFixtures:

  def sha256(parts: Array[Byte]*): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    parts.foreach(digest.update)
    digest.digest()

  def utf8(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)

  def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  def p256(): KeyPair =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()

  /** A certificate and the key that signed it. */
  final case class Authority(certificate: X509Certificate, key: PrivateKey)

  private var serials = 1000L

  private def nextSerial(): BigInteger = synchronized:
    serials += 1
    BigInteger.valueOf(serials)

  /** A certificate authority, self-signed or issued by `issuer`. */
  def authority(subject: String, issuer: Option[Authority] = None): Authority =
    val pair = p256()
    Authority(issue(subject, pair, issuer, ca = true, serial = nextSerial(), extensions = Nil), pair.getPrivate)

  /** A certificate for `pair`'s public key, issued by `issuer`, or self-signed when there is none, which only an authority is. */
  def issue(
      subject: String,
      pair: KeyPair,
      issuer: Option[Authority],
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
    generator.setNotAfter(Date(System.currentTimeMillis() + 3 * 86_400_000))
    generator.setPublicKey(pair.getPublic)
    generator.setSignatureAlgorithm("SHA256withECDSA")
    if ca then generator.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    extensions.foreach((oid, value) => generator.addExtension(oid, false, value))
    generator.generate(issuer.fold(pair.getPrivate)(_.key))

  // ── CBOR ──────────────────────────────────────────────────────────────────────────────────

  def cbor(item: Cbor): Array[Byte] =
    def head(major: Int, argument: Long): Array[Byte] =
      if argument < 24 then Array(((major << 5) | argument.toInt).toByte)
      else if argument < 256 then Array(((major << 5) | 24).toByte, argument.toByte)
      else if argument < 65536 then Array(((major << 5) | 25).toByte, (argument >> 8).toByte, argument.toByte)
      else ByteBuffer.allocate(5).put(((major << 5) | 26).toByte).putInt(argument.toInt).array()
    item match
      case Cbor.Unsigned(value) => head(0, value)
      case Cbor.Negative(value) => head(1, value)
      case Cbor.Bytes(value) => head(2, value.length) ++ value
      case Cbor.Text(value) => head(3, utf8(value).length) ++ utf8(value)
      case Cbor.Arr(items) => head(4, items.length) ++ items.flatMap(cbor)
      case Cbor.Mapping(entries) => head(5, entries.length) ++ entries.flatMap((key, value) => cbor(key) ++ cbor(value))

  def map(entries: (String, Cbor)*): Cbor = Cbor.Mapping(entries.map((key, value) => Cbor.Text(key) -> value).toVector)

  // ── Apple ─────────────────────────────────────────────────────────────────────────────────

  val ProductionAaguid: Array[Byte] = utf8("appattest") ++ Array.fill[Byte](7)(0)
  val DevelopmentAaguid: Array[Byte] = utf8("appattestdevelop")

  /** The identity App Attest names a key by: the SHA-256 of its public point (`0x04 || X || Y`), which is the last 65 bytes of the
    * key's encoding. */
  def keyId(pair: KeyPair): Array[Byte] = sha256(pair.getPublic.getEncoded.takeRight(65))

  /** Apple's chain: a root, an intermediate, and the credential certificate over the attested key. */
  final case class Apple(root: Authority, intermediate: Authority):
    def roots: Set[X509Certificate] = Set(root.certificate)

  def apple(): Apple =
    val root = authority("CN=Test App Attestation Root CA,O=Test")
    Apple(root, authority("CN=Test App Attestation CA 1,O=Test", Some(root)))

  /** What can be made wrong about an attestation. */
  final case class AppleOptions(
      counter: Long = 0,
      aaguid: Array[Byte] = ProductionAaguid,
      nonce: Option[Array[Byte]] = None,
      credentialId: Option[Array[Byte]] = None,
      rpIdSource: Option[String] = None,
      format: String = "apple-appattest",
  )

  def authenticatorData(appId: String, counter: Long, attested: Option[(Array[Byte], Array[Byte])]): Array[Byte] =
    val fixed = sha256(utf8(appId)) ++ Array[Byte](if attested.isDefined then 0x40 else 0) ++ ByteBuffer.allocate(4).putInt(counter.toInt).array()
    attested.fold(fixed): (aaguid, credentialId) =>
      fixed ++ aaguid ++ ByteBuffer.allocate(2).putShort(credentialId.length.toShort).array() ++ credentialId

  def appleAttestation(
      chain: Apple,
      pair: KeyPair,
      appId: String,
      clientDataHash: Array[Byte],
      options: AppleOptions = AppleOptions(),
  ): Array[Byte] =
    val id = keyId(pair)
    val authData = authenticatorData(options.rpIdSource.getOrElse(appId), options.counter, Some(options.aaguid -> options.credentialId.getOrElse(id)))
    val nonce = options.nonce.getOrElse(sha256(authData, clientDataHash))
    val credential = issue(
      subject = "CN=credential",
      pair = pair,
      issuer = Some(chain.intermediate),
      ca = false,
      serial = nextSerial(),
      extensions = List("1.2.840.113635.100.8.2" -> DERSequence(DERTaggedObject(true, 1, DEROctetString(nonce)))),
    )
    cbor(
      map(
        "fmt" -> Cbor.Text(options.format),
        "attStmt" -> map(
          "x5c" -> Cbor.Arr(Vector(Cbor.Bytes(credential.getEncoded), Cbor.Bytes(chain.intermediate.certificate.getEncoded))),
          "receipt" -> Cbor.Bytes(Array.fill[Byte](16)(1)),
        ),
        "authData" -> Cbor.Bytes(authData),
      ),
    )

  /** An assertion by `pair` for `clientDataHash`: the signature is ECDSA over SHA-256 of the nonce, as Apple's key makes it. */
  def appleAssertion(
      pair: KeyPair,
      appId: String,
      counter: Long,
      clientDataHash: Array[Byte],
      signedClientData: Option[Array[Byte]] = None,
  ): Array[Byte] =
    val authData = authenticatorData(appId, counter, None)
    val nonce = sha256(authData, signedClientData.getOrElse(clientDataHash))
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(pair.getPrivate)
    signer.update(nonce)
    cbor(map("signature" -> Cbor.Bytes(signer.sign()), "authenticatorData" -> Cbor.Bytes(authData)))

  // ── Android ───────────────────────────────────────────────────────────────────────────────

  final case class Google(root: Authority, intermediate: Authority):
    def roots: Set[X509Certificate] = Set(root.certificate)

  def google(): Google =
    val root = authority("CN=Test Key Attestation Root,O=Test")
    Google(root, authority("CN=Test Key Attestation CA,O=Test", Some(root)))

  /** What can be made wrong about a key description. */
  final case class AndroidOptions(
      attestationLevel: Int = 1,
      keyMintLevel: Int = 1,
      locked: Boolean = true,
      bootState: Int = 0,
      rootOfTrust: Boolean = true,
      rootOfTrustInSoftware: Boolean = false,
      origin: Option[Int] = Some(0),
      packages: List[String] = List("com.example.app"),
      digests: List[Array[Byte]] = List(sha256(utf8("signing certificate"))),
      serial: BigInteger = BigInteger.valueOf(424242),
  )

  def keyDescription(challenge: Array[Byte], options: AndroidOptions): DERSequence =
    val applicationId = DERSequence(
      Array[ASN1Encodable](
        DERSet(options.packages.map(name => DERSequence(Array[ASN1Encodable](DEROctetString(utf8(name)), ASN1Integer(1)))).toArray[ASN1Encodable]),
        DERSet(options.digests.map(digest => DEROctetString(digest)).toArray[ASN1Encodable]),
      ),
    )
    val rootOfTrust = DERSequence(
      Array[ASN1Encodable](
        DEROctetString(Array.fill[Byte](32)(3)),
        ASN1Boolean.getInstance(options.locked),
        ASN1Enumerated(options.bootState),
        DEROctetString(Array.fill[Byte](32)(4)),
      ),
    )
    val applicationIdEntry = DERTaggedObject(true, 709, DEROctetString(applicationId.getEncoded))
    val software = DERSequence(
      (List[ASN1Encodable](applicationIdEntry) ++
        Option.when(options.rootOfTrust && options.rootOfTrustInSoftware)(DERTaggedObject(true, 704, rootOfTrust))).toArray,
    )
    val hardware = DERSequence(
      (options.origin.map(value => DERTaggedObject(true, 702, ASN1Integer(value))).toList ++
        Option.when(options.rootOfTrust && !options.rootOfTrustInSoftware)(DERTaggedObject(true, 704, rootOfTrust))).toArray[ASN1Encodable],
    )
    DERSequence(
      Array[ASN1Encodable](
        ASN1Integer(4),
        ASN1Enumerated(options.attestationLevel),
        ASN1Integer(100),
        ASN1Enumerated(options.keyMintLevel),
        DEROctetString(challenge),
        DEROctetString(Array.emptyByteArray),
        software,
        hardware,
      ),
    )

  /** The chain an Android Keystore makes for `pair`: the leaf, the intermediate and, as Android sends it, the root last. */
  def androidChain(chain: Google, pair: KeyPair, challenge: Array[Byte], options: AndroidOptions = AndroidOptions()): List[Array[Byte]] =
    val leaf = issue(
      subject = "CN=Android Keystore Key",
      pair = pair,
      issuer = Some(chain.intermediate),
      ca = false,
      serial = options.serial,
      extensions = List("1.3.6.1.4.1.11129.2.1.17" -> keyDescription(challenge, options)),
    )
    List(leaf, chain.intermediate.certificate, chain.root.certificate).map(_.getEncoded)
