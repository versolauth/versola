package versola.edge.attestation

import versola.edge.attestation.AttestationFailure.Kind
import versola.edge.attestation.AttestationFixtures.*
import zio.test.*

import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Instant

/** The App Attest rules, against evidence built to Apple's documented format. */
object AppAttestSpec extends ZIOSpecDefault:

  private val appId = "ABCDE12345.com.example.app"
  private val clientData = sha256(utf8("this request"))
  private val now = Instant.now()

  private def kind[A](result: Either[AttestationFailure, A]): Option[Kind] = result.left.toOption.map(_.kind)

  private def attest(
      chain: Apple = apple(),
      pair: KeyPair = p256(),
      options: AppleOptions = AppleOptions(),
      appIds: Set[String] = Set(appId),
      environment: IosEnvironment = IosEnvironment.production,
      hash: Array[Byte] = clientData,
      roots: Option[Set[X509Certificate]] = None,
      keyId: Option[Array[Byte]] = None,
  ) =
    val evidence = appleAttestation(chain, pair, appId, clientData, options)
    val id = keyId.getOrElse(AttestationFixtures.keyId(pair))
    AppAttest.verifyAttestation(evidence, id, hash, appIds, environment, roots.getOrElse(chain.roots), now)

  def spec = suite("AppAttest")(
    suite("an attestation")(
      test("by a key Apple vouches for, made for this request and an accepted app, is accepted, and gives the key to keep") {
        val pair = p256()
        val result = attest(pair = pair)
        assertTrue(result.isRight, result.exists(_.publicKey.sameElements(pair.getPublic.getEncoded)))
      },
      test("a development key is accepted where the client accepts development, and refused where it accepts production") {
        val options = AppleOptions(aaguid = DevelopmentAaguid)
        assertTrue(
          attest(options = options, environment = IosEnvironment.development).isRight,
          kind(attest(options = options, environment = IosEnvironment.production)) == Some(Kind.DeviceNotAccepted),
          kind(attest(environment = IosEnvironment.development)) == Some(Kind.DeviceNotAccepted),
        )
      },
      test("an attestation made for another request is refused, since its nonce covers the hash the app handed Apple") {
        assertTrue(kind(attest(hash = sha256(utf8("another request")))) == Some(Kind.Mismatch))
      },
      test("a nonce that is not the hash of the authenticator data and the client data is refused") {
        assertTrue(kind(attest(options = AppleOptions(nonce = Some(sha256(utf8("other")))))) == Some(Kind.Mismatch))
      },
      test("an app that is not one of the accepted is refused, whichever App ID the evidence names") {
        assertTrue(
          kind(attest(appIds = Set("ABCDE12345.com.example.other"))) == Some(Kind.AppNotAccepted),
          kind(attest(options = AppleOptions(rpIdSource = Some("ZZZZZ99999.com.attacker")))) == Some(Kind.AppNotAccepted),
        )
      },
      test("any of several accepted apps will do") {
        assertTrue(attest(appIds = Set("ABCDE12345.com.example.beta", appId)).isRight)
      },
      test("a chain that does not lead to Apple's root is refused") {
        assertTrue(kind(attest(roots = Some(apple().roots))) == Some(Kind.UntrustedChain))
      },
      test("a key id that is not the hash of the attested key is refused") {
        assertTrue(kind(attest(keyId = Some(sha256(utf8("another key"))))) == Some(Kind.Mismatch))
      },
      test("a credential id that is not the key id is refused") {
        assertTrue(kind(attest(options = AppleOptions(credentialId = Some(Array.fill[Byte](32)(9))))) == Some(Kind.Mismatch))
      },
      test("an attestation whose counter is not zero is refused") {
        assertTrue(kind(attest(options = AppleOptions(counter = 3))) == Some(Kind.Replay))
      },
      test("an object in another format, or that is not CBOR, or is too large, is refused") {
        val pair = p256()
        val chain = apple()
        def verify(evidence: Array[Byte]) =
          AppAttest.verifyAttestation(evidence, keyId(pair), clientData, Set(appId), IosEnvironment.production, chain.roots, now)
        assertTrue(
          kind(verify(appleAttestation(chain, pair, appId, clientData, AppleOptions(format = "packed")))) == Some(Kind.Malformed),
          kind(verify(Array[Byte](1, 2, 3))) == Some(Kind.Malformed),
          kind(verify(Array.fill[Byte](20_000)(0))) == Some(Kind.Malformed),
        )
      },
    ),
    suite("an assertion")(
      test("by the attested key for this request, with the app, is accepted and gives its counter") {
        val pair = p256()
        val evidence = appleAssertion(pair, appId, counter = 7, clientData)
        assertTrue(AppAttest.verifyAssertion(evidence, clientData, pair.getPublic.getEncoded, Set(appId)) == Right(7L))
      },
      test("the counter is read as unsigned") {
        val pair = p256()
        val evidence = appleAssertion(pair, appId, counter = 0x80000001L, clientData)
        assertTrue(AppAttest.verifyAssertion(evidence, clientData, pair.getPublic.getEncoded, Set(appId)) == Right(0x80000001L))
      },
      test("one signed by another key is refused") {
        val evidence = appleAssertion(p256(), appId, 1, clientData)
        assertTrue(kind(AppAttest.verifyAssertion(evidence, clientData, p256().getPublic.getEncoded, Set(appId))) == Some(Kind.BadSignature))
      },
      test("one made for another request is refused, so that an observed assertion is good for one proof only") {
        val pair = p256()
        val evidence = appleAssertion(pair, appId, 1, clientData)
        val other = sha256(utf8("another request"))
        assertTrue(kind(AppAttest.verifyAssertion(evidence, other, pair.getPublic.getEncoded, Set(appId))) == Some(Kind.BadSignature))
      },
      test("one whose authenticator data was changed after signing is refused") {
        val pair = p256()
        val signedFor = appleAssertion(pair, appId, 1, clientData)
        val forged = appleAssertion(pair, appId, 2, clientData, signedClientData = Some(sha256(utf8("else"))))
        assertTrue(
          kind(AppAttest.verifyAssertion(forged, clientData, pair.getPublic.getEncoded, Set(appId))) == Some(Kind.BadSignature),
          AppAttest.verifyAssertion(signedFor, clientData, pair.getPublic.getEncoded, Set(appId)).isRight,
        )
      },
      test("one for an app the client does not accept is refused") {
        val pair = p256()
        val evidence = appleAssertion(pair, "ABCDE12345.com.example.other", 1, clientData)
        assertTrue(kind(AppAttest.verifyAssertion(evidence, clientData, pair.getPublic.getEncoded, Set(appId))) == Some(Kind.AppNotAccepted))
      },
      test("an assertion that does not parse, or is too large, is refused") {
        val pair = p256()
        assertTrue(
          kind(AppAttest.verifyAssertion(Array[Byte](1, 2), clientData, pair.getPublic.getEncoded, Set(appId))) == Some(Kind.Malformed),
          kind(AppAttest.verifyAssertion(Array.fill[Byte](5000)(0), clientData, pair.getPublic.getEncoded, Set(appId))) == Some(Kind.Malformed),
          kind(AppAttest.verifyAssertion(appleAssertion(pair, appId, 1, clientData), clientData, Array[Byte](1), Set(appId))) == Some(Kind.Malformed),
        )
      },
    ),
  )
