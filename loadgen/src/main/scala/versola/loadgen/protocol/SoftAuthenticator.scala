package versola.loadgen.protocol

import zio.json.*
import zio.json.ast.Json
import zio.{IO, ZIO}

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, Signature}
import java.util.UUID

/** A software WebAuthn authenticator, just complete enough for the registration and assertion
  * ceremonies of §8.3 -- one P-256 key pair per virtual user, a real COSE key in the attested
  * credential data, and a real ECDSA signature over `authenticatorData ‖ SHA-256(clientDataJSON)`,
  * so the yubico library on the server verifies it exactly as it would a browser's.
  *
  * Ported from `e2e/.../support/TestAuthenticator.scala` (dev spec §3.1, "copy verbatim,
  * rename") with the two changes the hot path needs: `SecureRandom`/`MessageDigest` come from
  * [[Entropy]]'s thread-locals rather than one shared instance each, and the ceremonies fail
  * typed into [[ProtocolError]] instead of into `Task`, so a malformed options document is an
  * error the campaign counts rather than a dead fiber (§3.2, §3.3).
  */
object SoftAuthenticator:
  /** The credential a [[create]] call minted, plus the JSON the browser would post back. */
  final case class Credential(id: String, responseJson: Json, privateKey: ECPrivateKey)

  private val registrationEndpoint = "/settings/passkeys/register/start"
  private val assertionEndpoint = "/challenge/passkey/options"

  /** `navigator.credentials.create()` against the options auth returned from the enrollment
    * start route. Runs in the seeder and the 0.5%/month enrolment process, not on the login
    * path (§8.3).
    */
  def create(publicKeyOptions: String, origin: String): IO[ProtocolError, Credential] =
    for
      options <- decode[CreationOptions](publicKeyOptions, registrationEndpoint)
      credential <- attempt(registrationEndpoint)(mint(options.publicKey.challenge, options.publicKey.rp.id, origin))
    yield credential

  /** `navigator.credentials.get()` against the options from `GET /challenge/passkey/options`,
    * signed with the key pair [[create]] minted. Returns the JSON the browser would post back.
    */
  def get(credential: Credential, publicKeyOptions: String, origin: String, userId: UUID): IO[ProtocolError, Json] =
    for
      options <- decode[RequestOptions](publicKeyOptions, assertionEndpoint)
      response <- attempt(assertionEndpoint)(sign(credential, options.publicKey.challenge, options.publicKey.rpId, origin, userId))
    yield response

  private def decode[A: JsonDecoder](body: String, endpoint: String): IO[ProtocolError, A] =
    ZIO.fromEither(body.fromJson[A]).mapError(error => ProtocolError.MalformedResponse(endpoint, error))

  /** A failing key generator or signer is the emulator's own problem, not a SUT measurement. */
  private def attempt[A](endpoint: String)(value: => A): IO[ProtocolError, A] =
    ZIO.attempt(value).mapError(error => ProtocolError.Misconfigured(endpoint + ": " + error.getMessage))

  private def mint(challenge: String, rpId: String, origin: String): Credential =
    val keyPair =
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"), Entropy.secureRandom)
      generator.generateKeyPair()
    val credentialId = Entropy.secureBytes(32)

    val clientDataJson = Json.Obj(
      "type" -> Json.Str("webauthn.create"),
      "challenge" -> Json.Str(challenge),
      "origin" -> Json.Str(origin),
      "crossOrigin" -> Json.Bool(false),
    ).toJson.getBytes(StandardCharsets.UTF_8)

    val authData = authenticatorData(rpId, credentialId, keyPair.getPublic.asInstanceOf[ECPublicKey])
    val attestationObject = Cbor.map(
      "fmt" -> Cbor.text("none"),
      "attStmt" -> Cbor.emptyMap,
      "authData" -> Cbor.bytes(authData),
    )

    val id = Entropy.base64Url(credentialId)
    Credential(
      id = id,
      responseJson = Json.Obj(
        "type" -> Json.Str("public-key"),
        "id" -> Json.Str(id),
        "response" -> Json.Obj(
          "attestationObject" -> Json.Str(Entropy.base64Url(attestationObject)),
          "clientDataJSON" -> Json.Str(Entropy.base64Url(clientDataJson)),
          "transports" -> Json.Arr(Json.Str("internal")),
        ),
        "clientExtensionResults" -> Json.Obj(),
      ),
      privateKey = keyPair.getPrivate.asInstanceOf[ECPrivateKey],
    )

  /** WebAuthn §6.3.3 authenticator data (no attested credential data, the credential already
    * exists) plus the ECDSA signature over it and the client data hash.
    */
  private def sign(credential: Credential, challenge: String, rpId: String, origin: String, userId: UUID): Json =
    val clientDataJson = Json.Obj(
      "type" -> Json.Str("webauthn.get"),
      "challenge" -> Json.Str(challenge),
      "origin" -> Json.Str(origin),
      "crossOrigin" -> Json.Bool(false),
    ).toJson.getBytes(StandardCharsets.UTF_8)

    val rpIdHash = Entropy.sha256(rpId)
    val authData = rpIdHash ++ Array[Byte](0x05) ++ Array[Byte](0, 0, 0, 0) // flags: UP | UV, signCount: 0

    val clientDataHash = Entropy.sha256(clientDataJson)
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(credential.privateKey, Entropy.secureRandom)
    signer.update(authData ++ clientDataHash)
    val signature = signer.sign()

    val handle = ByteBuffer.allocate(16)
    handle.putLong(userId.getMostSignificantBits)
    handle.putLong(userId.getLeastSignificantBits)

    Json.Obj(
      "type" -> Json.Str("public-key"),
      "id" -> Json.Str(credential.id),
      "response" -> Json.Obj(
        "authenticatorData" -> Json.Str(Entropy.base64Url(authData)),
        "clientDataJSON" -> Json.Str(Entropy.base64Url(clientDataJson)),
        "signature" -> Json.Str(Entropy.base64Url(signature)),
        "userHandle" -> Json.Str(Entropy.base64Url(handle.array())),
      ),
      "clientExtensionResults" -> Json.Obj(),
    )

  /** WebAuthn §6.1: rpIdHash ‖ flags ‖ signCount ‖ attested credential data. Flags are
    * UP | UV | AT -- a freshly created credential is always user-present, and the tenant's
    * enrollment settings may require user verification.
    */
  private def authenticatorData(rpId: String, credentialId: Array[Byte], publicKey: ECPublicKey): Array[Byte] =
    val out = ByteArrayOutputStream()
    out.write(Entropy.sha256(rpId))
    out.write(0x45)
    out.write(Array[Byte](0, 0, 0, 0)) // signature counter
    out.write(Array.ofDim[Byte](16)) // all-zero AAGUID: this authenticator has no model identity
    out.write(Array((credentialId.length >> 8).toByte, credentialId.length.toByte))
    out.write(credentialId)
    out.write(coseKey(publicKey))
    out.toByteArray

  /** RFC 9052 §7: an EC2 key over P-256 with ES256, in CTAP2 canonical key order. */
  private def coseKey(publicKey: ECPublicKey): Array[Byte] =
    Cbor.map(
      1 -> Cbor.int(2), // kty: EC2
      3 -> Cbor.int(-7), // alg: ES256
      -1 -> Cbor.int(1), // crv: P-256
      -2 -> Cbor.bytes(coordinate(publicKey.getW.getAffineX)),
      -3 -> Cbor.bytes(coordinate(publicKey.getW.getAffineY)),
    )

  /** Left-pads (or drops the sign byte of) a coordinate to the fixed 32-byte field size. */
  private def coordinate(value: BigInteger): Array[Byte] =
    val bytes = value.toByteArray
    if bytes.length == 32 then bytes
    else if bytes.length > 32 then bytes.takeRight(32)
    else Array.ofDim[Byte](32 - bytes.length) ++ bytes

  /** The subset of `navigator.credentials.create()` options this authenticator reads. */
  private case class CreationOptions(publicKey: PublicKeyOptions) derives JsonDecoder
  private case class PublicKeyOptions(challenge: String, rp: RelyingParty) derives JsonDecoder
  private case class RelyingParty(id: String) derives JsonDecoder

  /** The subset of `navigator.credentials.get()` options this authenticator reads. */
  private case class RequestOptions(publicKey: RequestPublicKeyOptions) derives JsonDecoder
  private case class RequestPublicKeyOptions(challenge: String, rpId: String) derives JsonDecoder

  /** Just enough of RFC 8949 to write the two fixed structures above. */
  private object Cbor:
    val emptyMap: Array[Byte] = head(5, 0)

    def int(value: Int): Array[Byte] =
      if value >= 0 then head(0, value) else head(1, -1 - value)

    def text(value: String): Array[Byte] =
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      head(3, bytes.length) ++ bytes

    def bytes(value: Array[Byte]): Array[Byte] =
      head(2, value.length) ++ value

    def map(entries: (String | Int, Array[Byte])*): Array[Byte] =
      entries.foldLeft(head(5, entries.length)):
        case (acc, (key: String, value)) => acc ++ text(key) ++ value
        case (acc, (key: Int, value)) => acc ++ int(key) ++ value

    private def head(major: Int, value: Int): Array[Byte] =
      val prefix = (major << 5).toByte
      if value < 24 then Array((prefix | value).toByte)
      else if value < 256 then Array((prefix | 24).toByte, value.toByte)
      else if value < 65536 then Array((prefix | 25).toByte, (value >> 8).toByte, value.toByte)
      else Array((prefix | 26).toByte, (value >> 24).toByte, (value >> 16).toByte, (value >> 8).toByte, value.toByte)
