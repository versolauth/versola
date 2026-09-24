package versola.util

import com.nimbusds.jose.jwk.AsymmetricJWK
import zio.json.*
import zio.prelude.Equal
import zio.schema.Schema

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, MessageDigest, PrivateKey, Signature}
import javax.security.auth.x500.X500Principal
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** The certificate and private key a party holds in order to authenticate *as* a client over
  * mutual TLS (RFC 8705 §2) -- `versola.edge.SSOClient` presenting it to whatever terminates
  * TLS in front of auth.
  *
  * The mirror of `versola.oauth.mtls.ClientCertificate`, which is the same certificate as auth
  * sees it: without the private key, already terminated and forwarded by a proxy. The two read
  * the certificate for the same facts and must agree about them, which is why the subject and
  * key comparisons here are written to the same rules -- a registration this accepts and auth
  * then refuses would be worse than no validation at all.
  *
  * Carried as the PEM text rather than as parsed material because that is what presenting it
  * requires: the TLS stack reads it back from a file, and re-serializing a parsed certificate
  * would change bytes that the far side hashes (`x5t#S256`).
  */
case class PrivateClientCertificate(pem: String):

  /** The certificate and key as a TLS stack needs them, or the reason this PEM cannot present
    * anything. */
  def material: Either[String, PrivateClientCertificate.Material] =
    PrivateClientCertificate.parse(pem)

object PrivateClientCertificate:

  /** A PEM block's type and body, with the delimiters the block was written with kept: what is
    * handed to the TLS stack has to be the text that was registered, not a re-encoding of it.
    */
  private case class Block(label: String, text: String, body: Array[Byte])

  private val BlockPattern: Regex =
    """-----BEGIN ([A-Z0-9 ]+)-----([^-]*)-----END \1-----""".r

  /** RFC 8705 §2.1.2 subject types, named as the registration names them, so a caller holding
    * a registered `subjectType` can ask for the values it would be compared against without
    * this having to model the enum a second time. */
  private val SanTypes = Map(
    1 -> "san_email",
    2 -> "san_dns",
    6 -> "san_uri",
    7 -> "san_ip",
  )

  /** A validated pair: the PEM text each half is presented as, and the leaf certificate the
    * far side authenticates by.
    *
    * The two texts are separate because the TLS stack reads them separately -- Netty, which is
    * what ultimately presents this, takes a certificate chain and a key as two inputs.
    */
  case class Material(
      certificateChain: String,
      privateKeyPem: String,
      leaf: X509Certificate,
  ):
    /** RFC 4514 string form of the subject, rendered as auth renders the one it compares
      * against. */
    def subjectDn: String = leaf.getSubjectX500Principal.getName(X500Principal.RFC2253)

    /** The values a `san_*` subject type would be matched against, empty for a certificate
      * carrying no such name. An unreadable SAN extension reads as absent, as it does at auth:
      * a certificate is still recognised by its `subject_dn`. */
    def subjectAlternativeNames: Map[String, Set[String]] =
      Try(Option(leaf.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil))
        .getOrElse(Nil)
        .flatMap: entry =>
          entry.asScala.toList match
            case (tag: Integer) :: (value: String) :: Nil => SanTypes.get(tag.intValue).map(_ -> value)
            case _ => None
        .groupMap(_._1)(_._2)
        .view.mapValues(_.toSet).toMap

    /** The value a client registered under `subjectType`, as auth would read it off this
      * certificate. `subject_dn` is the subject itself; everything else is an alternative
      * name, of which a certificate may carry several. */
    def subjectValues(subjectType: String): Set[String] =
      if subjectType == "subject_dn" then Set(subjectDn)
      else subjectAlternativeNames.getOrElse(subjectType, Set.empty)

    /** RFC 8705 §2.2: whether the key inside this certificate is one `keySet` publishes --
      * the whole of what a self-signed registration is matched by. Compared on the DER
      * `SubjectPublicKeyInfo`, which is what auth compares. */
    def publishedIn(keySet: JsonWebKeySet): Boolean =
      val encoded = leaf.getPublicKey.getEncoded
      keySet.publicKeys.toOption.exists(
        _.keys.getKeys.asScala.exists:
          case key: AsymmetricJWK => Try(MessageDigest.isEqual(key.toPublicKey.getEncoded, encoded)).getOrElse(false)
          case _ => false,
      )

  /** Accepts a PEM this server could actually present: a certificate chain, a private key the
    * TLS stack can read, and the two belonging together.
    *
    * The pairing is checked by signing with the key and verifying with the certificate rather
    * than by comparing key parameters, which would need a case per key type and would quietly
    * pass the one it had no case for.
    */
  def validate(pem: String): Either[String, PrivateClientCertificate] =
    parse(pem).map(_ => PrivateClientCertificate(pem))

  private def parse(pem: String): Either[String, Material] =
    for
      blocks <- Right(BlockPattern.findAllMatchIn(pem).toList.flatMap(decode))
      certificates <- Right(blocks.filter(_.label == "CERTIFICATE"))
      _ <- Either.cond(certificates.nonEmpty, (), "must contain a CERTIFICATE block")
      key <- privateKeyBlock(blocks)
      leaf <- certificate(certificates.head.body)
      privateKey <- readPrivateKey(key.body)
      _ <- pairs(leaf, privateKey)
    yield Material(
      certificateChain = certificates.map(_.text).mkString("\n"),
      privateKeyPem = key.text,
      leaf = leaf,
    )

  private def decode(matched: Regex.Match): Option[Block] =
    Try(java.util.Base64.getMimeDecoder.decode(matched.group(2))).toOption
      .map(Block(matched.group(1), matched.matched, _))

  /** PKCS#8 only, and exactly one. Netty reads no other form, so a PKCS#1 `RSA PRIVATE KEY`
    * would be registered here and then fail where nothing can explain it; a second key would
    * leave which one is presented to the order of the blocks. */
  private def privateKeyBlock(blocks: List[Block]): Either[String, Block] =
    blocks.filter(_.label.endsWith("PRIVATE KEY")) match
      case (block @ Block("PRIVATE KEY", _, _)) :: Nil => Right(block)
      case Nil => Left("must contain a PRIVATE KEY block")
      case Block(label, _, _) :: Nil =>
        Left(s"carries a '$label' block, and only an unencrypted PKCS#8 'PRIVATE KEY' can be presented")
      case _ => Left("must contain exactly one PRIVATE KEY block")

  private def certificate(der: Array[Byte]): Either[String, X509Certificate] =
    Try(
      CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)),
    ).toEither.left.map(error => s"is not a valid X.509 certificate: ${error.getMessage}")
      .flatMap:
        case x509: X509Certificate => Right(x509)
        case other => Left(s"is not an X.509 certificate: ${other.getType}")

  /** The key types RFC 8705 leaves usable here are the ones this server signs with elsewhere,
    * so the same two are accepted and the algorithm is found by trying them: PKCS#8 names it
    * in the encoding, but reading it out costs a parser this does not otherwise need. */
  private def readPrivateKey(der: Array[Byte]): Either[String, PrivateKey] =
    val spec = PKCS8EncodedKeySpec(der)
    List("RSA", "EC")
      .flatMap(algorithm => Try(KeyFactory.getInstance(algorithm).generatePrivate(spec)).toOption)
      .headOption
      .toRight("carries a private key that is neither an RSA nor an EC key in PKCS#8 form")

  private def pairs(leaf: X509Certificate, privateKey: PrivateKey): Either[String, Unit] =
    val algorithm = privateKey.getAlgorithm match
      case "EC" => "SHA256withECDSA"
      case _ => "SHA256withRSA"
    val challenge = "versola".getBytes(StandardCharsets.UTF_8)

    Try {
      val signer = Signature.getInstance(algorithm)
      signer.initSign(privateKey)
      signer.update(challenge)
      val signature = signer.sign()

      val verifier = Signature.getInstance(algorithm)
      verifier.initVerify(leaf.getPublicKey)
      verifier.update(challenge)
      verifier.verify(signature)
    }.toEither.left
      .map(error => s"could not be checked against its certificate: ${error.getMessage}")
      .flatMap(Either.cond(_, (), "carries a private key that does not belong to its certificate"))

  given Schema[PrivateClientCertificate] = Schema.primitive[String].transform(
    PrivateClientCertificate(_),
    _.pem,
  )

  given JsonCodec[PrivateClientCertificate] =
    JsonCodec(JsonEncoder[String].contramap(_.pem), JsonDecoder[String].map(PrivateClientCertificate(_)))

  given Equal[PrivateClientCertificate] = (a, b) => a == b

  given CanEqual[PrivateClientCertificate, PrivateClientCertificate] = CanEqual.derived
