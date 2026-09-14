package versola.loadgen.seed

import zio.test.*

import java.security.SecureRandom

/** The COSE encoding, byte by byte.
  *
  * [[PasskeyMaterial]] writes these 77 bytes into `passkeys.public_key`, from where auth hands
  * them straight to the yubico library as `publicKeyCose` on every assertion -- so a framing
  * error is a passkey cohort that can never log in, and the failure surfaces as a WebAuthn
  * verification error with nothing pointing at the seeder.
  *
  * The constants are asserted as literals rather than derived, which is the point: they come from
  * RFC 9052 §7 and CTAP2's canonical key order, not from anything in this repository, so a test
  * that recomputed them from the code under test would confirm whatever the code happened to do.
  */
object PasskeyMaterialSpec extends ZIOSpecDefault:

  private val random = SecureRandom()

  def spec = suite("PasskeyMaterial")(
    test("the COSE key is the canonical 77-byte ES256 P-256 encoding") {
      val material = PasskeyMaterial.generate(random)
      val cose = material.publicKeyCose
      assertTrue(
        cose.length == 77,
        cose(0) == 0xa5.toByte, // five-entry map
        cose(1) == 0x01.toByte, // key 1: kty
        cose(2) == 0x02.toByte, //   EC2
        cose(3) == 0x03.toByte, // key 3: alg
        cose(4) == 0x26.toByte, //   ES256 (-7, encoded as major 1 / value 6)
        cose(5) == 0x20.toByte, // key -1: crv
        cose(6) == 0x01.toByte, //   P-256
        cose(7) == 0x21.toByte, // key -2: x
        cose(8) == 0x58.toByte, //   byte string, one-byte length
        cose(9) == 0x20.toByte, //   32 bytes
        cose(42) == 0x22.toByte, // key -3: y
        cose(43) == 0x58.toByte,
        cose(44) == 0x20.toByte,
      )
    },
    // A coordinate shorter than 32 bytes must be left-padded, not written short: CBOR declares
    // the length above, so a 31-byte coordinate would shift every following byte and produce a
    // structurally invalid key roughly one time in 256.
    test("coordinates are fixed width, and are the key's actual coordinates") {
      val material = PasskeyMaterial.generate(random)
      val recovered = PasskeyAssertions.publicKeyOf(material.publicKeyCose)
      assertTrue(
        PasskeyMaterial.cose(recovered).toSeq == material.publicKeyCose.toSeq,
        recovered.getW.getAffineX.signum() > 0,
        recovered.getW.getAffineY.signum() > 0,
      )
    },
    test("the stored COSE key is the public half of the stored private key") {
      val material = PasskeyMaterial.generate(random)
      assertTrue(PasskeyAssertions.signsWith(material.privateKey, material.publicKeyCose))
    },
    test("two credentials never share a key or an id") {
      val first = PasskeyMaterial.generate(random)
      val second = PasskeyMaterial.generate(random)
      assertTrue(
        first.credentialId.length == PasskeyMaterial.CredentialIdBytes,
        first.credentialId.toSeq != second.credentialId.toSeq,
        first.publicKeyCose.toSeq != second.publicKeyCose.toSeq,
        first.privateKey.toSeq != second.privateKey.toSeq,
        !PasskeyAssertions.signsWith(first.privateKey, second.publicKeyCose),
      )
    },
    test("the credential id is the base64url form the driver's authenticator echoes back") {
      val material = PasskeyMaterial.generate(random)
      val encoded = SeedRows.credentialIdOf(material)
      assertTrue(
        // 32 bytes unpadded base64url
        encoded.length == 43,
        !encoded.contains("="),
        !encoded.contains("+"),
        !encoded.contains("/"),
        java.util.Base64.getUrlDecoder.decode(encoded).toSeq == material.credentialId.toSeq,
      )
    },
  )
