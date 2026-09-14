package versola.loadgen.seed

import versola.util.Secret

import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, SecureRandom}

/** A P-256 credential for one seeded passkey user (versola-loadgen-dev-spec.md §10 step 3): the
  * private key the driver's software authenticator will sign assertions with, and the COSE public
  * key auth stores and verifies them against.
  *
  * @param credentialId
  *   32 random bytes, the `passkeys.id` primary key and the `id` the assertion echoes back
  * @param privateKey
  *   PKCS#8, as `vu_users.passkey_key` holds it and
  *   [[versola.loadgen.protocol.SoftAuthenticator]] reads it
  * @param publicKeyCose
  *   RFC 9052 §7 `COSE_Key`, the exact bytes `WebAuthnService` persists as
  *   `result.getPublicKeyCose.getBytes` and feeds back as `publicKeyCose` on every assertion
  */
case class PasskeyMaterial(credentialId: Array[Byte], privateKey: Secret, publicKeyCose: Array[Byte])

object PasskeyMaterial:

  /** The COSE encoding is written out as a fixed byte layout rather than through a CBOR encoder,
    * and rather than through `SoftAuthenticator`'s -- whose `coseKey` is private, and whose file
    * belongs to another track in flight.
    *
    * That is a fork, and dev spec §3.4 is about not taking those lightly, so: for an ES256 key
    * over P-256 there is exactly one canonical encoding and it is 77 bytes of constant structure
    * around two 32-byte coordinates. There is nothing here to drift *to* -- the structure is
    * fixed by RFC 9052 §7 and CTAP2's canonical key order, not by anything either repository
    * decides -- and `PasskeyMaterialSpec` pins every constant byte of it. The duplication to
    * retire is the general CBOR encoder in `SoftAuthenticator`, which goes when `protocol/`
    * becomes its own module (§3.4's post-v1 item), not this.
    *
    * Byte for byte: `a5` (5-entry map), `01 02` (kty: EC2), `03 26` (alg: ES256, -7),
    * `20 01` (crv: -1 -> P-256), `21 58 20` + x, `22 58 20` + y.
    */
  private val CoseHeader: Array[Byte] =
    Array(0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20).map(_.toByte)

  private val CoseYPrefix: Array[Byte] = Array(0x22, 0x58, 0x20).map(_.toByte)

  /** P-256 field size. A coordinate is left-padded to it, because CBOR carries a byte string of
    * declared length and `BigInteger.toByteArray` is neither fixed-width nor unsigned.
    */
  private val CoordinateBytes = 32

  val CredentialIdBytes = 32

  /** `SecureRandom` is passed in rather than created per call: the seeder mints one of these per
    * passkey-cohort user across every core, and `SecureRandom` synchronises internally.
    * [[versola.loadgen.protocol.Entropy]] solves the same problem with a thread-local; the seeder
    * does not need one, because its parallelism is bounded and its cost centre is Argon2.
    */
  def generate(random: SecureRandom): PasskeyMaterial =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"), random)
    val keyPair = generator.generateKeyPair()
    val credentialId = Array.ofDim[Byte](CredentialIdBytes)
    random.nextBytes(credentialId)
    PasskeyMaterial(
      credentialId = credentialId,
      privateKey = Secret(keyPair.getPrivate.getEncoded),
      publicKeyCose = cose(keyPair.getPublic.asInstanceOf[ECPublicKey]),
    )

  def cose(publicKey: ECPublicKey): Array[Byte] =
    CoseHeader ++
      coordinate(publicKey.getW.getAffineX) ++
      CoseYPrefix ++
      coordinate(publicKey.getW.getAffineY)

  private def coordinate(value: java.math.BigInteger): Array[Byte] =
    val bytes = value.toByteArray
    if bytes.length == CoordinateBytes then bytes
    else if bytes.length > CoordinateBytes then bytes.takeRight(CoordinateBytes)
    else Array.ofDim[Byte](CoordinateBytes - bytes.length) ++ bytes
