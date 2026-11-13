package versola.edge.attestation

import com.nimbusds.jose.jwk.{Curve, ECKey}
import versola.edge.attestation.AttestationFailure.Kind
import versola.edge.attestation.AttestationFixtures.*
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

/** What the policy of a client makes of what an app sent: which modes look, which refuse, and what edge keeps between requests. */
object AttestationServiceSpec extends ZIOSpecDefault:

  private val clientId = "mobile-app"
  private val appId = "ABCDE12345.com.example.app"
  private val jkt = "thumbprint-of-the-dpop-key"
  private val signing = sha256(utf8("signing certificate"))

  private val ios = IosApps(appIds = Set(appId), environment = IosEnvironment.production)
  private val android = AndroidApp(packageName = "com.example.app", certificateDigests = Set(hex(signing)))
  private def policy(mode: AttestationMode, withIos: Boolean = true, withAndroid: Boolean = true) =
    AttestationPolicy(mode = mode, ios = Option.when(withIos)(ios), android = if withAndroid then List(android) else Nil)

  private val enforce = policy(AttestationMode.enforce)

  /** The repository, in memory, and how often it was asked. */
  private final class Keys(state: Ref[Map[(String, String), AttestedKey]], val asked: Ref[Int], fails: Boolean) extends AttestedKeyRepository:
    private def touch[A](effect: UIO[A]): Task[A] =
      asked.update(_ + 1) *> (if fails then ZIO.fail(RuntimeException("database is down")) else effect)
    def register(client: String, keyId: String, publicKey: Array[Byte], environment: IosEnvironment, at: Instant) =
      touch(state.update(m => if m.contains(client -> keyId) then m else m.updated(client -> keyId, AttestedKey(publicKey, environment, 0))))
    def find(client: String, keyId: String) = touch(state.get.map(_.get(client -> keyId)))
    def advance(client: String, keyId: String, counter: Long, at: Instant) =
      touch(state.modify: m =>
        m.get(client -> keyId) match
          case Some(key) if key.counter < counter => (true, m.updated(client -> keyId, key.copy(counter = counter)))
          case _ => (false, m))

  private final case class Env(service: AttestationService, keys: Keys, chain: Apple, google: Google)

  private def env(fails: Boolean = false): UIO[Env] =
    for
      state <- Ref.make(Map.empty[(String, String), AttestedKey])
      asked <- Ref.make(0)
      chain = apple()
      android = google()
      keys = Keys(state, asked, fails)
    yield Env(AttestationService.Impl(AttestationTrust.fixed(chain.roots, android.roots), keys), keys, chain, android)

  private def b64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  private def hashFor(jti: String) = AttestationService.clientDataHash(clientId, jkt, jti)

  private def evidence(
      platform: Option[String] = None,
      keyId: Option[String] = None,
      attestation: Option[Array[Byte]] = None,
      assertion: Option[Array[Byte]] = None,
      chain: List[Array[Byte]] = Nil,
  ) = AttestationEvidence(platform, keyId, attestation.map(b64), assertion.map(b64), chain.map(b64))

  private def iosAttestation(e: Env, pair: KeyPair, jti: String) =
    evidence(Some("ios"), Some(b64(keyId(pair))), attestation = Some(appleAttestation(e.chain, pair, appId, hashFor(jti))))

  private def iosAssertion(pair: KeyPair, jti: String, counter: Long) =
    evidence(Some("ios"), Some(b64(keyId(pair))), assertion = Some(appleAssertion(pair, appId, counter, hashFor(jti))))

  private def kindOf(effect: IO[AttestationFailure, Unit]): UIO[Option[Kind]] = effect.either.map(_.left.toOption.map(_.kind))

  def spec = suite("AttestationService")(
    suite("the mode of the policy")(
      test("off does not look at what the app sent, nor consult the store") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, policy(AttestationMode.off), jkt, "jti", evidence(Some("ios"), Some("garbage"))))
          asked <- e.keys.asked.get
        yield assertTrue(result.isEmpty, asked == 0)
      },
      test("enforce refuses an app that sent nothing") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence()))
        yield assertTrue(result == Some(Kind.Absent))
      },
      test("report lets through what enforce would refuse, whether nothing was sent or what was is wrong") {
        for
          e <- env()
          reporting = policy(AttestationMode.report)
          nothing <- kindOf(e.service.verify(clientId, reporting, jkt, "jti", evidence()))
          wrong <- kindOf(e.service.verify(clientId, reporting, jkt, "jti", evidence(Some("android"), chain = List(Array[Byte](1, 2)))))
          refused <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence(Some("android"), chain = List(Array[Byte](1, 2)))))
        yield assertTrue(nothing.isEmpty, wrong.isEmpty, refused == Some(Kind.Malformed))
      },
      test("a platform that is neither ios nor android is malformed") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence(Some("windows"))))
        yield assertTrue(result == Some(Kind.Malformed))
      },
    ),
    suite("iOS")(
      test("a key is attested once and then asserts, each assertion for one proof") {
        val pair = p256()
        for
          e <- env()
          attested <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-1", iosAttestation(e, pair, "jti-1")))
          first <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", iosAssertion(pair, "jti-2", 1)))
          second <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", iosAssertion(pair, "jti-3", 2)))
        yield assertTrue(attested.isEmpty, first.isEmpty, second.isEmpty)
      },
      test("an assertion used a second time is refused, and so is one whose counter is behind") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", iosAttestation(e, pair, "jti-1"))
          used <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", iosAssertion(pair, "jti-2", 5)))
          again <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", iosAssertion(pair, "jti-2", 5)))
          behind <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", iosAssertion(pair, "jti-3", 4)))
        yield assertTrue(used.isEmpty, again == Some(Kind.Replay), behind == Some(Kind.Replay))
      },
      test("an assertion made for another proof is refused, since the proof's jti is in what the key signed") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", iosAttestation(e, pair, "jti-1"))
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-other", iosAssertion(pair, "jti-2", 1)))
        yield assertTrue(result == Some(Kind.BadSignature))
      },
      test("an assertion by a key edge has not been shown is refused as unknown, which is how an app learns to attest it") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", iosAssertion(p256(), "jti", 1)))
        yield assertTrue(result == Some(Kind.UnknownKey))
      },
      test("a key attested in the development environment stops asserting when the policy accepts only production") {
        val pair = p256()
        val development = AttestationPolicy(AttestationMode.enforce, Some(IosApps(Set(appId), IosEnvironment.development)), Nil)
        for
          e <- env()
          attested <- kindOf(
            e.service.verify(
              clientId,
              development,
              jkt,
              "jti-1",
              evidence(
                Some("ios"),
                Some(b64(keyId(pair))),
                attestation = Some(appleAttestation(e.chain, pair, appId, hashFor("jti-1"), AppleOptions(aaguid = DevelopmentAaguid))),
              ),
            ),
          )
          before <- kindOf(e.service.verify(clientId, development, jkt, "jti-2", iosAssertion(pair, "jti-2", 1)))
          after <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", iosAssertion(pair, "jti-3", 2)))
        yield assertTrue(attested.isEmpty, before.isEmpty, after == Some(Kind.DeviceNotAccepted))
      },
      test("a key attested for one client is not known to another") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", iosAttestation(e, pair, "jti-1"))
          result <- kindOf(e.service.verify("another-app", enforce, jkt, "jti-2", iosAssertion(pair, "jti-2", 1)))
        yield assertTrue(result == Some(Kind.UnknownKey))
      },
      test("attesting a key again does not set its counter back") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", iosAttestation(e, pair, "jti-1"))
          _ <- e.service.verify(clientId, enforce, jkt, "jti-2", iosAssertion(pair, "jti-2", 9))
          _ <- e.service.verify(clientId, enforce, jkt, "jti-3", iosAttestation(e, pair, "jti-3"))
          replay <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-4", iosAssertion(pair, "jti-4", 9)))
        yield assertTrue(replay == Some(Kind.Replay))
      },
      test("the key id may be written standard or URL-safe, padded or not") {
        val pair = p256()
        val id = keyId(pair)
        val forms = List(
          Base64.getEncoder.encodeToString(id),
          Base64.getUrlEncoder.encodeToString(id),
          Base64.getUrlEncoder.withoutPadding.encodeToString(id),
        )
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-0", iosAttestation(e, pair, "jti-0"))
          results <- ZIO.foreach(forms.zipWithIndex)((form, i) =>
            kindOf(e.service.verify(clientId, enforce, jkt, s"jti-$i", iosAssertion(pair, s"jti-$i", i + 1L).copy(keyId = Some(form)))),
          )
        yield assertTrue(results.forall(_.isEmpty))
      },
      test("an attestation and an assertion together, a missing key id, and neither, are refused") {
        val pair = p256()
        for
          e <- env()
          both <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", iosAssertion(pair, "jti", 1).copy(attestationObject = Some("AAAA"))))
          noKey <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", iosAssertion(pair, "jti", 1).copy(keyId = None)))
          neither <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence(Some("ios"), Some(b64(keyId(pair))))))
        yield assertTrue(both == Some(Kind.Malformed), noKey == Some(Kind.Malformed), neither == Some(Kind.Absent))
      },
      test("a client that accepts no iOS build refuses an iOS app") {
        val pair = p256()
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, policy(AttestationMode.enforce, withIos = false), jkt, "jti", iosAttestation(e, pair, "jti")))
        yield assertTrue(result == Some(Kind.Unsupported))
      },
      test("a value larger than an attestation is refused before it is decoded") {
        val pair = p256()
        for
          e <- env()
          oversized = evidence(Some("ios"), Some(b64(keyId(pair)))).copy(attestationObject = Some("A" * 30_000))
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", oversized))
        yield assertTrue(result == Some(Kind.Malformed))
      },
    ),
    suite("Android")(
      test("a chain for the DPoP key, made for this client, is accepted with nothing remembered") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          result <- kindOf(e.service.verify(clientId, enforce, thumbprintOf(pair), "jti", evidence(Some("android"), chain = chain)))
          asked <- e.keys.asked.get
        yield assertTrue(result.isEmpty, asked == 0)
      },
      test("a chain made for another client, and a chain for another key, are refused") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          otherClient <- kindOf(e.service.verify("another-app", enforce, thumbprintOf(pair), "jti", evidence(Some("android"), chain = chain)))
          otherKey <- kindOf(e.service.verify(clientId, enforce, thumbprintOf(p256()), "jti", evidence(Some("android"), chain = chain)))
        yield assertTrue(otherClient == Some(Kind.Mismatch), otherKey == Some(Kind.Mismatch))
      },
      test("more certificates than a chain has are refused before any is decoded") {
        for
          e <- env()
          tooMany = AttestationEvidence(Some("android"), None, None, None, List.fill(7)("AAAA"))
          result <- e.service.verify(clientId, enforce, jkt, "jti", tooMany).either
        yield assertTrue(result.left.exists(f => f.kind == Kind.Malformed && f.detail.contains("more than")))
      },
      test("no chain, a client that accepts no Android build, and a value that is not base64, are refused") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          none <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence(Some("android"))))
          iosOnly = policy(AttestationMode.enforce, withAndroid = false)
          unsupported <- kindOf(e.service.verify(clientId, iosOnly, thumbprintOf(pair), "jti", evidence(Some("android"), chain = chain)))
          garbage <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", AttestationEvidence(Some("android"), None, None, None, List("***"))))
        yield assertTrue(none == Some(Kind.Absent), unsupported == Some(Kind.Unsupported), garbage == Some(Kind.Malformed))
      },
    ),
    suite("when the store the attestation needs is down")(
      test("report goes on, and enforce does not pretend the app was wrong: the request fails to be retried") {
        val pair = p256()
        for
          e <- env(fails = true)
          reported <- kindOf(e.service.verify(clientId, policy(AttestationMode.report), jkt, "jti", iosAttestation(e, pair, "jti")))
          enforced <- e.service.verify(clientId, enforce, jkt, "jti", iosAttestation(e, pair, "jti")).exit
        yield assertTrue(reported.isEmpty, enforced.isFailure, enforced.causeOption.exists(_.isDie))
      },
    ),
    test("a platform the app made up is not a metric label, whatever it wrote") {
      def series(platform: String) =
        Metric.counter("attestation_requests_total")
          .tagged(Set(MetricLabel("platform", platform), MetricLabel("mode", "enforce"), MetricLabel("result", "Malformed")))
          .value.map(_.count)
      for
        e <- env()
        _ <- ZIO.foreachDiscard(1 to 5)(i => kindOf(e.service.verify(clientId, enforce, jkt, "jti", evidence(Some(s"made-up-$i")))))
        made <- series("made-up-1")
        folded <- series("none")
      yield assertTrue(made == 0.0, folded >= 0.0)
    },
    test("the client data hash is bound to the client, the key and the proof") {
      val base = AttestationService.clientDataHash("a", "k", "j")
      assertTrue(
        !base.sameElements(AttestationService.clientDataHash("b", "k", "j")),
        !base.sameElements(AttestationService.clientDataHash("a", "l", "j")),
        !base.sameElements(AttestationService.clientDataHash("a", "k", "i")),
        !AttestationService.androidChallenge("a").sameElements(AttestationService.androidChallenge("b")),
      )
    },
  ) @@ TestAspect.withLiveClock

  private def thumbprintOf(pair: KeyPair): String =
    ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build().computeThumbprint().toString
