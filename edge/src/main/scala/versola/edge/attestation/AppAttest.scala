package versola.edge.attestation

import org.bouncycastle.asn1.{ASN1OctetString, ASN1Primitive, ASN1Sequence, ASN1TaggedObject}
import versola.edge.attestation.AttestationCrypto.*
import versola.edge.attestation.AttestationFailure.Kind

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey, Signature}
import java.security.cert.X509Certificate
import java.time.Instant
import scala.util.Try

/** Apple's App Attest (DeviceCheck), the server half: what an app proves with a key the Secure Enclave made and Apple attested.
  *
  * Two artefacts. The **attestation** is made once per key, and says that Apple vouches the key lives in a genuine app's Secure
  * Enclave; its certificate chain leads to Apple's App Attestation root. The **assertion** is made for each request with that key
  * and says that the same key signed this request. Neither can sign anything but what the app hands App Attest -- the key cannot
  * be used as the DPoP key -- so the request is tied to the DPoP key by what the app hands it: `clientDataHash`, which the caller
  * builds from the proof's key and `jti` (see [[AttestationService.clientDataHash]]).
  *
  * Written against Apple's "Validating apps that connect to your server". Everything here is pure: the caller holds the state (a
  * key once attested, and the counter of its assertions).
  */
private[attestation] object AppAttest:

  /** The OID of the certificate extension that carries the nonce Apple bound the attestation to. */
  private val NonceExtension = "1.2.840.113635.100.8.2"

  private val MaxObjectLength = 16 * 1024
  private val MaxAssertionLength = 2 * 1024

  /** `aaguid` of a production key: the ASCII `appattest` followed by seven zero bytes; a development one spells `appattestdevelop`. */
  private val ProductionAaguid = "appattest".getBytes(StandardCharsets.US_ASCII) ++ Array.fill[Byte](7)(0)
  private val DevelopmentAaguid = "appattestdevelop".getBytes(StandardCharsets.US_ASCII)

  /** An attested key, as it is kept to check its assertions with. */
  final case class Attested(keyId: Array[Byte], publicKey: Array[Byte])

  /** The attestation of a key, held to everything Apple documents: the chain, the nonce, the key's identity, the app and the
    * environment. `clientDataHash` is the one the app handed `attestKey`; the key is attested for exactly the request that carried it. */
  def verifyAttestation(
      attestationObject: Array[Byte],
      keyId: Array[Byte],
      clientDataHash: Array[Byte],
      appIds: Set[String],
      environment: IosEnvironment,
      roots: Set[X509Certificate],
      now: Instant,
  ): Either[AttestationFailure, Attested] =
    for
      _ <- Either.cond(attestationObject.length <= MaxObjectLength, (), malformed("the attestation object is too large"))
      decoded <- Cbor.decode(attestationObject).left.map(malformed)
      _ <- Either.cond(
        decoded.get("fmt").flatMap(_.text).contains("apple-appattest"),
        (),
        malformed("the attestation is not in the apple-appattest format"),
      )
      statement <- decoded.get("attStmt").toRight(malformed("the attestation has no statement"))
      authData <- decoded.get("authData").flatMap(_.bytes).toRight(malformed("the attestation has no authData"))
      chainDer <- statement.get("x5c") match
        case Some(Cbor.Arr(items)) =>
          val parts = items.map(_.bytes)
          Either.cond(parts.forall(_.isDefined), parts.flatten.toList, malformed("x5c holds something that is not a certificate"))
        case _ => Left(malformed("the attestation has no certificate chain"))
      chain <- certificates(chainDer)
      _ <- validate(chain, roots, now)
      credential = chain.head

      // The nonce Apple put in the certificate is over the authData and the hash the app gave it: the attestation is for this request.
      nonce = sha256(authData, clientDataHash)
      attested <- nonceOf(credential).toRight(malformed("the credential certificate carries no nonce"))
      _ <- Either.cond(equal(attested, nonce), (), AttestationFailure(Kind.Mismatch, "the attestation was not made for this request"))

      // The key's identity is the hash of its public point, and the certificate must be for that key.
      point <- uncompressed(credential.getPublicKey).toRight(malformed("the credential key is not a P-256 key"))
      _ <- Either.cond(equal(sha256(point), keyId), (), AttestationFailure(Kind.Mismatch, "the key id is not the hash of the attested key"))

      parsed <- AuthData.parse(authData, withCredential = true)
      _ <- Either.cond(
        appIds.exists(appId => equal(parsed.rpIdHash, sha256(appId.getBytes(StandardCharsets.UTF_8)))),
        (),
        AttestationFailure(Kind.AppNotAccepted, "the attestation is for an app this client does not accept"),
      )
      _ <- Either.cond(parsed.counter == 0L, (), AttestationFailure(Kind.Replay, "an attestation starts the counter at zero"))
      _ <- Either.cond(
        parsed.aaguid.exists(aaguid => equal(aaguid, if environment == IosEnvironment.production then ProductionAaguid else DevelopmentAaguid)),
        (),
        AttestationFailure(Kind.DeviceNotAccepted, s"the key is not from the $environment App Attest environment"),
      )
      _ <- Either.cond(
        parsed.credentialId.exists(equal(_, keyId)),
        (),
        AttestationFailure(Kind.Mismatch, "the credential id is not the key id"),
      )
    yield Attested(keyId = keyId, publicKey = credential.getPublicKey.getEncoded)

  /** An assertion by a key that was attested: the signature, the app, and the counter it carries. The caller then moves the stored
    * counter to it, atomically, and refuses the assertion if that does not move it forward -- that is what makes it single use. */
  def verifyAssertion(
      assertion: Array[Byte],
      clientDataHash: Array[Byte],
      publicKey: Array[Byte],
      appIds: Set[String],
  ): Either[AttestationFailure, Long] =
    for
      _ <- Either.cond(assertion.length <= MaxAssertionLength, (), malformed("the assertion is too large"))
      decoded <- Cbor.decode(assertion).left.map(malformed)
      signature <- decoded.get("signature").flatMap(_.bytes).toRight(malformed("the assertion has no signature"))
      authData <- decoded.get("authenticatorData").flatMap(_.bytes).toRight(malformed("the assertion has no authenticatorData"))
      parsed <- AuthData.parse(authData, withCredential = false)
      _ <- Either.cond(
        appIds.exists(appId => equal(parsed.rpIdHash, sha256(appId.getBytes(StandardCharsets.UTF_8)))),
        (),
        AttestationFailure(Kind.AppNotAccepted, "the assertion is for an app this client does not accept"),
      )
      key <- Try(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))).toEither
        .left.map(_ => malformed("the stored key does not parse"))
      // The signature is over the nonce, and ECDSA hashes what it signs: the digest that was signed is SHA-256 of the nonce.
      nonce = sha256(authData, clientDataHash)
      _ <- Either.cond(signs(key, nonce, signature), (), AttestationFailure(Kind.BadSignature, "the assertion's signature does not verify"))
    yield parsed.counter

  private def signs(key: PublicKey, message: Array[Byte], signature: Array[Byte]): Boolean =
    Try:
      val verifier = Signature.getInstance("SHA256withECDSA")
      verifier.initVerify(key)
      verifier.update(message)
      verifier.verify(signature)
    .getOrElse(false)

  /** The octet string inside the extension, however the sequence around it is tagged. */
  private def nonceOf(certificate: X509Certificate): Option[Array[Byte]] =
    extensionValue(certificate, NonceExtension).flatMap: der =>
      def octets(value: Any): Option[Array[Byte]] = value match
        case string: ASN1OctetString => Some(string.getOctets)
        case sequence: ASN1Sequence => if sequence.size > 0 then octets(sequence.getObjectAt(0)) else None
        case tagged: ASN1TaggedObject => octets(tagged.getExplicitBaseObject)
        case _ => None
      Try(ASN1Primitive.fromByteArray(der)).toOption.flatMap(octets)

  /** The key's point in the form its identity is hashed over: `0x04 || X || Y`, each coordinate 32 bytes. */
  private def uncompressed(key: PublicKey): Option[Array[Byte]] = key match
    case ec: ECPublicKey if ec.getParams.getCurve.getField.getFieldSize == 256 =>
      def fixed(value: java.math.BigInteger): Array[Byte] =
        val raw = value.toByteArray.dropWhile(_ == 0)
        Array.fill[Byte](32 - raw.length)(0) ++ raw
      Some(Array[Byte](4) ++ fixed(ec.getW.getAffineX) ++ fixed(ec.getW.getAffineY))
    case _ => None

  /** The authenticator data both artefacts carry: the hash of the app id, flags, a counter, and -- in an attestation -- the key's
    * credential. */
  private final case class AuthData(
      rpIdHash: Array[Byte],
      counter: Long,
      aaguid: Option[Array[Byte]],
      credentialId: Option[Array[Byte]],
  )

  private object AuthData:
    private val Fixed = 37

    def parse(data: Array[Byte], withCredential: Boolean): Either[AttestationFailure, AuthData] =
      if data.length < Fixed then Left(malformed("the authenticator data is too short"))
      else
        val buffer = ByteBuffer.wrap(data)
        val rpIdHash = data.slice(0, 32)
        val counter = buffer.getInt(33).toLong & 0xffffffffL
        if !withCredential then Right(AuthData(rpIdHash, counter, None, None))
        else if data.length < Fixed + 16 + 2 then Left(malformed("the authenticator data carries no credential"))
        else
          val length = buffer.getShort(Fixed + 16).toInt & 0xffff
          if data.length < Fixed + 18 + length then Left(malformed("the credential id is longer than the authenticator data"))
          else Right(AuthData(rpIdHash, counter, Some(data.slice(Fixed, Fixed + 16)), Some(data.slice(Fixed + 18, Fixed + 18 + length))))
