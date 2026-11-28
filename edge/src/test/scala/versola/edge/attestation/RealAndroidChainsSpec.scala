package versola.edge.attestation

import com.nimbusds.jose.jwk.{Curve, ECKey}
import versola.edge.attestation.AttestationFixtures.hex
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.io.{ByteArrayInputStream, InputStream}
import java.math.BigInteger
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** The Android verifier against chains real devices made, which the other specs cannot be: they build their evidence from the format's
  * documentation, with the same reading of it that the verifier has.
  *
  * The chains are the test data of Google's `android/keyattestation` (Apache 2.0, commit c391708, `testdata/`, see
  * `src/test/resources/attestation/google/README.md`): one remotely provisioned chain (RKP) and three factory-provisioned ones, from
  * devices of 2017 to 2023, to the roots that ship with edge. Each comes with the values Google's own verifier read from it, which
  * is what the app and the challenge here are taken from.
  */
object RealAndroidChainsSpec extends ZIOSpecDefault:

  private def resource(name: String): String =
    val stream: InputStream = getClass.getResourceAsStream(s"/attestation/google/$name")
    try String(stream.readAllBytes(), "UTF-8") finally stream.close()

  private val factory = CertificateFactory.getInstance("X.509")

  private def certificates(pem: String): List[X509Certificate] =
    factory.generateCertificates(ByteArrayInputStream(pem.getBytes("UTF-8"))).asScala.collect { case c: X509Certificate => c }.toList

  /** The roots edge ships with. */
  private val roots: Set[X509Certificate] =
    certificates(String(getClass.getResourceAsStream("/attestation/android-key-attestation-roots.pem").readAllBytes(), "UTF-8")).toSet

  private final case class Captured(chain: List[X509Certificate], challenge: Array[Byte], app: AndroidApp, createdAt: Instant)

  private def captured(name: String): Captured =
    val chain = certificates(resource(s"$name.pem"))
    val json = resource(s"$name.json").fromJson[Json.Obj].toOption.get
    def obj(of: Json.Obj, field: String) = of.get(field).flatMap(_.asObject).get
    def text(of: Json.Obj, field: String) = of.get(field).collect { case Json.Str(value) => value }.get
    val software = obj(json, "softwareEnforced")
    val application = obj(software, "attestationApplicationId")
    val packages = application.get("packages").collect { case Json.Arr(values) => values.toList.flatMap(_.asObject).map(text(_, "name")) }.get
    val signatures = application.get("signatures").collect { case Json.Arr(values) => values.toList.collect { case Json.Str(v) => v } }.get
    // `creationDateTime` is milliseconds since the epoch where the key was made; a chain of older devices has none, and the factory chains
    // are not held to their validity.
    val created = software.get("creationDateTime").collect { case Json.Str(v) => Instant.ofEpochMilli(v.toLong) }.getOrElse(Instant.now())
    Captured(
      chain,
      Base64.getDecoder.decode(text(json, "attestationChallenge")),
      AndroidApp(packages.head, signatures.map(sig => hex(Base64.getDecoder.decode(sig))).toSet),
      created,
    )

  private def jkt(chain: List[X509Certificate]): String =
    ECKey.Builder(Curve.P_256, chain.head.getPublicKey.asInstanceOf[ECPublicKey]).build().computeThumbprint().toString

  private def verify(name: String, at: Option[Instant] = None, revoked: BigInteger => Boolean = _ => false) =
    val c = captured(name)
    AndroidKeyAttestation.verify(c.chain.map(_.getEncoded), c.challenge, List(c.app), jkt(c.chain), roots, revoked, at.getOrElse(c.createdAt))

  def spec = suite("AndroidKeyAttestation against chains real devices made")(
    test("a remotely provisioned chain (Pixel 9 Pro, Android 16, 2025) is accepted, as of the time the key was made") {
      assertTrue(verify("caiman-sdk36-TEE_EC_RKP").isRight)
    },
    test("the same remotely provisioned chain, long after its certificates have expired, is refused: RKP validity is checked") {
      assertTrue(verify("caiman-sdk36-TEE_EC_RKP", at = Some(Instant.parse("2035-01-01T00:00:00Z"))).left.map(_.kind) == Left(AttestationFailure.Kind.UntrustedChain))
    },
    test("chains of Pixel 2 (2017) and Pixel 3 (2018), factory-provisioned and expired, are read through to the boot state, which is unlocked in both: refused for that, not for the chain") {
      def refusal(name: String) = verify(name, at = Some(Instant.parse("2035-01-01T00:00:00Z"))).left.map(f => f.kind -> f.detail)
      assertTrue(
        refusal("walleye-sdk27-TEE_EC_NONE") == Left(AttestationFailure.Kind.DeviceNotAccepted -> "the device is unlocked or did not boot verified"),
        refusal("blueline-sdk28-TEE_EC_NONE") == Left(AttestationFailure.Kind.DeviceNotAccepted -> "the device is unlocked or did not boot verified"),
      )
    },
    test("the chain of a Pixel 7a (2024), whose certificates are short-lived, is read through to the boot state while they are valid, and refused for the chain once they are not") {
      assertTrue(
        verify("akita-sdk34-TEE_EC_NONE", at = Some(Instant.parse("2024-09-20T00:00:00Z"))).left.map(_.kind) == Left(AttestationFailure.Kind.DeviceNotAccepted),
        verify("akita-sdk34-TEE_EC_NONE", at = Some(Instant.parse("2035-01-01T00:00:00Z"))).left.map(_.kind) == Left(AttestationFailure.Kind.UntrustedChain),
      )
    },
    test("a real chain is refused for another key, another challenge and another app") {
      val c = captured("caiman-sdk36-TEE_EC_RKP")
      val der = c.chain.map(_.getEncoded)
      def run(challenge: Array[Byte] = c.challenge, app: AndroidApp = c.app, key: String = jkt(c.chain)) =
        AndroidKeyAttestation.verify(der, challenge, List(app), key, roots, _ => false, c.createdAt).left.map(_.kind)
      assertTrue(
        run(key = "another-key") == Left(AttestationFailure.Kind.Mismatch),
        run(challenge = Array[Byte](1, 2, 3)) == Left(AttestationFailure.Kind.Mismatch),
        run(app = c.app.copy(packageName = "com.attacker.app")) == Left(AttestationFailure.Kind.AppNotAccepted),
      )
    },
  )
