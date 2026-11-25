package versola.edge.attestation

import com.nimbusds.jose.jwk.{Curve, ECKey}
import versola.edge.attestation.AttestationFailure.Kind
import versola.edge.attestation.AttestationFixtures.*

import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import zio.test.*

/** The Android rules, against a chain built to the documented `KeyDescription`. */
object AndroidKeyAttestationSpec extends ZIOSpecDefault:

  private val challenge = AttestationService.androidChallenge("mobile-app")
  private val signing = sha256(utf8("signing certificate"))
  private val app = AndroidApp(packageName = "com.example.app", certificateDigests = Set(hex(signing)))
  private val now = Instant.now()

  /** The thumbprint a DPoP proof by this key carries, computed the way the proof's own library does. */
  private def jkt(pair: KeyPair): String =
    ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build().computeThumbprint().toString

  private def kind[A](result: Either[AttestationFailure, A]): Option[Kind] = result.left.toOption.map(_.kind)

  private def verify(
      chain: Google = google(),
      pair: KeyPair = p256(),
      options: AndroidOptions = AndroidOptions(),
      expected: Array[Byte] = challenge,
      apps: List[AndroidApp] = List(app),
      key: Option[String] = None,
      roots: Option[Set[X509Certificate]] = None,
      revoked: BigInteger => Boolean = _ => false,
      chainOf: Option[List[Array[Byte]]] = None,
  ) =
    val der = chainOf.getOrElse(androidChain(chain, pair, challenge, options))
    AndroidKeyAttestation.verify(der, expected, apps, key.getOrElse(jkt(pair)), roots.getOrElse(chain.roots), revoked, now)

  def spec = suite("AndroidKeyAttestation")(
    test("a chain to Google's root for the DPoP key, held in hardware, by an accepted app on a locked, verified device, is accepted") {
      assertTrue(verify().isRight)
    },
    test("a key in StrongBox is reported as such") {
      assertTrue(verify(options = AndroidOptions(attestationLevel = 2, keyMintLevel = 2)) == Right(AndroidKeyAttestation.Verified(strongBox = true, app = app)))
    },
    test("a key that is not the DPoP key of the request is refused, so a chain is no use to anyone without its key") {
      assertTrue(kind(verify(key = Some(jkt(p256())))) == Some(Kind.Mismatch))
    },
    test("a chain made for another client is refused") {
      assertTrue(kind(verify(expected = AttestationService.androidChallenge("another-app"))) == Some(Kind.Mismatch))
    },
    test("a key held in software is refused, at either level") {
      assertTrue(
        kind(verify(options = AndroidOptions(attestationLevel = 0))) == Some(Kind.DeviceNotAccepted),
        kind(verify(options = AndroidOptions(keyMintLevel = 0))) == Some(Kind.DeviceNotAccepted),
      )
    },
    test("a device with an unlocked bootloader, or that did not boot verified, or that attests no root of trust, is refused") {
      assertTrue(
        kind(verify(options = AndroidOptions(locked = false))) == Some(Kind.DeviceNotAccepted),
        kind(verify(options = AndroidOptions(bootState = 2))) == Some(Kind.DeviceNotAccepted),
        kind(verify(options = AndroidOptions(rootOfTrust = false))) == Some(Kind.DeviceNotAccepted),
      )
    },
    test("a key that was imported, or whose origin is not stated, is refused: its private half has been somewhere else") {
      assertTrue(
        kind(verify(options = AndroidOptions(origin = Some(2)))) == Some(Kind.DeviceNotAccepted),
        kind(verify(options = AndroidOptions(origin = Some(4)))) == Some(Kind.DeviceNotAccepted),
        kind(verify(options = AndroidOptions(origin = None))) == Some(Kind.DeviceNotAccepted),
      )
    },
    test("a root of trust that is in the software-enforced list is not the hardware's statement, and is refused") {
      assertTrue(kind(verify(options = AndroidOptions(rootOfTrustInSoftware = true))) == Some(Kind.DeviceNotAccepted))
    },
    test("an app that is not one of the accepted is refused, by package or by signing certificate") {
      assertTrue(
        kind(verify(options = AndroidOptions(packages = List("com.attacker.app")))) == Some(Kind.AppNotAccepted),
        kind(verify(options = AndroidOptions(digests = List(sha256(utf8("another certificate")))))) == Some(Kind.AppNotAccepted),
        kind(verify(options = AndroidOptions(digests = Nil))) == Some(Kind.AppNotAccepted),
      )
    },
    test("every certificate the app is signed by has to be one the client accepts") {
      val other = sha256(utf8("another certificate"))
      assertTrue(
        kind(verify(options = AndroidOptions(digests = List(signing, other)))) == Some(Kind.AppNotAccepted),
        verify(
          options = AndroidOptions(digests = List(signing, other)),
          apps = List(app.copy(certificateDigests = Set(hex(signing), hex(other)))),
        ).isRight,
      )
    },
    test("a chain that does not lead to Google's root is refused") {
      assertTrue(kind(verify(roots = Some(google().roots))) == Some(Kind.UntrustedChain))
    },
    test("a certificate on the revocation list is refused, whichever certificate of the chain it is") {
      val chain = google()
      assertTrue(
        kind(
          verify(chain = chain, options = AndroidOptions(serial = BigInteger.valueOf(777)), revoked = _ == BigInteger.valueOf(777)),
        ) == Some(Kind.Revoked),
        kind(verify(chain = chain, revoked = _ == chain.intermediate.certificate.getSerialNumber)) == Some(Kind.Revoked),
      )
    },
    test("the root may be left off the chain") {
      val chain = google()
      val pair = p256()
      val full = androidChain(chain, pair, challenge)
      assertTrue(verify(chain = chain, pair = pair, chainOf = Some(full.take(2))).isRight)
    },
    test("an empty chain, one that is too long, and one that does not parse are refused") {
      val chain = google()
      val pair = p256()
      val full = androidChain(chain, pair, challenge)
      assertTrue(
        kind(verify(chainOf = Some(Nil))) == Some(Kind.Malformed),
        kind(verify(chain = chain, pair = pair, chainOf = Some(List.fill(7)(full.head)))) == Some(Kind.Malformed),
        kind(verify(chainOf = Some(List(Array[Byte](1, 2, 3))))) == Some(Kind.Malformed),
      )
    },
    suite("where the key description is, and how long the chain is valid")(
      test("a second certificate with a key description, below an attested key that may sign, is refused: only the first one is the hardware's") {
        val chain = google()
        val genuine = p256()
        val forged = p256()
        val extended = extendedChain(chain, genuine, forged, challenge)
        assertTrue(kind(verify(chain = chain, pair = forged, chainOf = Some(extended))) == Some(Kind.Malformed))
      },
      test("the same forged leaf is not accepted for being the only one with a description either: the description must be the leaf's and alone") {
        // the attested key's own certificate without a description: the forged leaf is then the only one that has it, but it is not under the root's chain
        val chain = google()
        val genuine = p256()
        val forged = p256()
        val extended = extendedChain(chain, genuine, forged, challenge)
        assertTrue(kind(verify(chain = chain, pair = forged, chainOf = Some(extended.take(1) ++ extended.drop(2)))) == Some(Kind.UntrustedChain))
      },
      test("a remotely provisioned chain, with the provisioning information right above the leaf, is accepted") {
        val chain = google()
        val pair = p256()
        assertTrue(verify(chain = chain, pair = pair, chainOf = Some(provisionedChain(chain, pair, challenge))).isRight)
      },
      test("an expired remotely provisioned chain is refused: its validity is checked") {
        val chain = google()
        val pair = p256()
        val expired = provisionedChain(chain, pair, challenge, leafNotBefore = days(-30), leafNotAfter = days(-10))
        assertTrue(kind(verify(chain = chain, pair = pair, chainOf = Some(expired))) == Some(Kind.UntrustedChain))
      },
      test("a factory-provisioned chain to the root with Google's serial number is accepted though its attestation certificate has expired") {
        val chain = google(rootSubject = FactoryRootSubject, notBefore = days(-4000), notAfter = days(4000))
        val pair = p256()
        val expired = androidChain(chain, pair, challenge, leafNotBefore = days(-3000), leafNotAfter = days(-10))
        assertTrue(verify(chain = chain, pair = pair, chainOf = Some(expired)).isRight)
      },
      test("the same expired chain to a root that does not carry that serial number is refused") {
        val chain = google(notBefore = days(-4000), notAfter = days(4000))
        val pair = p256()
        val expired = androidChain(chain, pair, challenge, leafNotBefore = days(-3000), leafNotAfter = days(-10))
        assertTrue(kind(verify(chain = chain, pair = pair, chainOf = Some(expired))) == Some(Kind.UntrustedChain))
      },
      test("an attestation certificate that was never valid with the chain above it is refused whatever the root") {
        val chain = google(rootSubject = FactoryRootSubject, notBefore = days(-1), notAfter = days(400))
        val pair = p256()
        val before = androidChain(chain, pair, challenge, leafNotBefore = days(-3000), leafNotAfter = days(-2000))
        assertTrue(kind(verify(chain = chain, pair = pair, chainOf = Some(before))) == Some(Kind.UntrustedChain))
      },
      test("a copy of the root that the device sends, with the same key and another validity, is taken for the root, expired or not") {
        val chain = google()
        val pair = p256()
        val genuine = androidChain(chain, pair, challenge).take(2)
        val expiredCopy = copyOf(chain.root, notBefore = days(-4000), notAfter = days(-5)).getEncoded
        val laterCopy = copyOf(chain.root, notBefore = days(-1), notAfter = days(3000)).getEncoded
        assertTrue(
          verify(chain = chain, pair = pair, chainOf = Some(genuine :+ expiredCopy)).isRight,
          verify(chain = chain, pair = pair, chainOf = Some(genuine :+ laterCopy)).isRight,
        )
      },
      test("a self-signed certificate with another key is not a copy of the root, whatever it is called") {
        val chain = google()
        val pair = p256()
        val genuine = androidChain(chain, pair, challenge).take(2)
        val impostor = authority("CN=Test Key Attestation Root,O=Test").certificate.getEncoded
        assertTrue(kind(verify(chain = chain, pair = pair, chainOf = Some(genuine :+ impostor))) == Some(Kind.UntrustedChain))
      },
    ),
    test("a leaf with no key description is refused") {
      val chain = google()
      val pair = p256()
      val leaf = issue("CN=leaf", pair, Some(chain.intermediate), ca = false, serial = BigInteger.TEN, extensions = Nil)
      val withoutDescription = List(leaf, chain.intermediate.certificate, chain.root.certificate).map(_.getEncoded)
      assertTrue(kind(verify(chain = chain, pair = pair, chainOf = Some(withoutDescription))) == Some(Kind.Malformed))
    },
  )
