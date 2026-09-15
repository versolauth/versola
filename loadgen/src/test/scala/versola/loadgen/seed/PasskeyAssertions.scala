package versola.loadgen.seed

import java.security.spec.{ECPoint, ECPublicKeySpec, PKCS8EncodedKeySpec}
import java.security.{AlgorithmParameters, KeyFactory, Signature}
import java.security.interfaces.ECPublicKey
import java.security.spec.{ECGenParameterSpec, ECParameterSpec}

/** Reconstructs a P-256 public key from the COSE bytes [[PasskeyMaterial]] writes into
  * `passkeys.public_key`, and checks it against a signature made with the matching private key.
  *
  * This is what stands in for the yubico library, which is not on `loadgen`'s classpath: the
  * question it answers is the one that matters -- do the 77 bytes stored as `public_key` decode
  * to the public half of the key pair whose private half the driver will sign assertions with. A
  * seeded passkey whose COSE key belongs to a different key pair is a credential auth rejects on
  * every assertion, and nothing about that failure points at the seeder.
  *
  * It does not check the CBOR *framing* against what the library expects -- `PasskeyMaterialSpec`
  * pins that byte by byte against RFC 9052 §7 instead.
  */
object PasskeyAssertions:

  def signsWith(privateKeyPkcs8: Array[Byte], cose: Array[Byte]): Boolean =
    val factory = KeyFactory.getInstance("EC")
    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
    val publicKey = publicKeyOf(cose)
    val payload = "authenticatorData || SHA-256(clientDataJSON)".getBytes("UTF-8")

    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(privateKey)
    signer.update(payload)
    val signature = signer.sign()

    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(payload)
    verifier.verify(signature)

  /** The inverse of [[PasskeyMaterial.cose]]: the two 32-byte coordinates at their fixed offsets
    * in the canonical ES256/P-256 encoding.
    */
  def publicKeyOf(cose: Array[Byte]): ECPublicKey =
    require(cose.length == 77, s"expected a 77-byte ES256 COSE_Key, got ${cose.length}")
    val x = BigInt(1, cose.slice(10, 42)).bigInteger
    val y = BigInt(1, cose.slice(45, 77)).bigInteger
    val parameters = AlgorithmParameters.getInstance("EC")
    parameters.init(ECGenParameterSpec("secp256r1"))
    val curve = parameters.getParameterSpec(classOf[ECParameterSpec])
    KeyFactory
      .getInstance("EC")
      .generatePublic(ECPublicKeySpec(ECPoint(x, y), curve))
      .asInstanceOf[ECPublicKey]
