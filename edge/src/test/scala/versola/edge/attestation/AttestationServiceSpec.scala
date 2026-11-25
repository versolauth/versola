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
      touch(state.modify(m =>
        if m.contains(client -> keyId) then (false, m) else (true, m.updated(client -> keyId, AttestedKey(publicKey, environment, 0))),
      ))
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
    yield Env(AttestationService.Impl(AttestationTrust.fixed(chain.roots, android.roots), keys, PlayIntegrity.unconfigured), keys, chain, android)

  private def b64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  private def hashFor(jti: String, client: String = clientId) = AttestationService.clientDataHash(client, jkt, jti)

  private def evidence(
      platform: Option[String] = None,
      keyId: Option[String] = None,
      attestation: Option[Array[Byte]] = None,
      assertion: Option[Array[Byte]] = None,
      chain: List[Array[Byte]] = Nil,
  ) = AttestationEvidence(platform, keyId, attestation.map(b64), assertion.map(b64), chain.map(b64))

  private def iosAttestation(e: Env, pair: KeyPair, jti: String, client: String = clientId) =
    evidence(Some("ios"), Some(b64(keyId(pair))), attestation = Some(appleAttestation(e.chain, pair, appId, hashFor(jti, client))))

  private def iosAssertion(pair: KeyPair, jti: String, counter: Long) =
    evidence(Some("ios"), Some(b64(keyId(pair))), assertion = Some(appleAssertion(pair, appId, counter, hashFor(jti))))

  /** The count of one series of `attestation_requests_total`; every label is part of the key. */
  private def series(client: String, platform: String, mode: String, result: String): UIO[Double] =
    Metric.counter("attestation_requests_total")
      .tagged(Set(MetricLabel("client", client), MetricLabel("platform", platform), MetricLabel("mode", mode), MetricLabel("result", result)))
      .value.map(_.count)

  private def kindOf(effect: IO[AttestationFailure, Unit]): UIO[Option[Kind]] = effect.either.map(_.left.toOption.map(_.kind))

  def spec = suite("AttestationService")(
    suite("the mode of the policy")(
      test("off does not look at what the app sent, nor consult the store") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, policy(AttestationMode.off), jkt, "jti", None, evidence(Some("ios"), Some("garbage"))))
          asked <- e.keys.asked.get
        yield assertTrue(result.isEmpty, asked == 0)
      },
      test("enforce refuses an app that sent nothing") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, evidence()))
        yield assertTrue(result == Some(Kind.Absent))
      },
      test("report lets through what enforce would refuse, whether nothing was sent or what was is wrong") {
        for
          e <- env()
          reporting = policy(AttestationMode.report)
          nothing <- kindOf(e.service.verify(clientId, reporting, jkt, "jti", None, evidence()))
          wrong <- kindOf(e.service.verify(clientId, reporting, jkt, "jti", None, evidence(Some("android"), chain = List(Array[Byte](1, 2)))))
          refused <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, evidence(Some("android"), chain = List(Array[Byte](1, 2)))))
        yield assertTrue(nothing.isEmpty, wrong.isEmpty, refused == Some(Kind.Malformed))
      },
      test("a platform that is neither ios nor android is malformed") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, evidence(Some("windows"))))
        yield assertTrue(result == Some(Kind.Malformed))
      },
    ),
    suite("iOS")(
      test("a key is attested once and then asserts, each assertion for one proof") {
        val pair = p256()
        for
          e <- env()
          attested <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1")))
          first <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 1)))
          second <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", None, iosAssertion(pair, "jti-3", 2)))
        yield assertTrue(attested.isEmpty, first.isEmpty, second.isEmpty)
      },
      test("an assertion used a second time is refused, and so is one whose counter is behind") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1"))
          used <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 5)))
          again <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 5)))
          behind <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", None, iosAssertion(pair, "jti-3", 4)))
        yield assertTrue(used.isEmpty, again == Some(Kind.Replay), behind == Some(Kind.Replay))
      },
      test("an assertion made for another proof is refused, since the proof's jti is in what the key signed") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1"))
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-other", None, iosAssertion(pair, "jti-2", 1)))
        yield assertTrue(result == Some(Kind.BadSignature))
      },
      test("an assertion by a key edge has not been shown is refused as unknown, which is how an app learns to attest it") {
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, iosAssertion(p256(), "jti", 1)))
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
              None,
              evidence(
                Some("ios"),
                Some(b64(keyId(pair))),
                attestation = Some(appleAttestation(e.chain, pair, appId, hashFor("jti-1"), AppleOptions(aaguid = DevelopmentAaguid))),
              ),
            ),
          )
          before <- kindOf(e.service.verify(clientId, development, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 1)))
          after <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-3", None, iosAssertion(pair, "jti-3", 2)))
        yield assertTrue(attested.isEmpty, before.isEmpty, after == Some(Kind.DeviceNotAccepted))
      },
      test("a key attested for one client is not known to another") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1"))
          result <- kindOf(e.service.verify("another-app", enforce, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 1)))
        yield assertTrue(result == Some(Kind.UnknownKey))
      },
      test("attesting a key again does not set its counter back") {
        val pair = p256()
        for
          e <- env()
          _ <- e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1"))
          _ <- e.service.verify(clientId, enforce, jkt, "jti-2", None, iosAssertion(pair, "jti-2", 9))
          _ <- e.service.verify(clientId, enforce, jkt, "jti-3", None, iosAttestation(e, pair, "jti-3"))
          replay <- kindOf(e.service.verify(clientId, enforce, jkt, "jti-4", None, iosAssertion(pair, "jti-4", 9)))
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
          _ <- e.service.verify(clientId, enforce, jkt, "jti-0", None, iosAttestation(e, pair, "jti-0"))
          results <- ZIO.foreach(forms.zipWithIndex)((form, i) =>
            kindOf(e.service.verify(clientId, enforce, jkt, s"jti-$i", None, iosAssertion(pair, s"jti-$i", i + 1L).copy(keyId = Some(form)))),
          )
        yield assertTrue(results.forall(_.isEmpty))
      },
      test("an attestation and an assertion together, a missing key id, and neither, are refused") {
        val pair = p256()
        for
          e <- env()
          both <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, iosAssertion(pair, "jti", 1).copy(attestationObject = Some("AAAA"))))
          noKey <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, iosAssertion(pair, "jti", 1).copy(keyId = None)))
          neither <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, evidence(Some("ios"), Some(b64(keyId(pair))))))
        yield assertTrue(both == Some(Kind.Malformed), noKey == Some(Kind.Malformed), neither == Some(Kind.Absent))
      },
      test("a client that accepts no iOS build refuses an iOS app") {
        val pair = p256()
        for
          e <- env()
          result <- kindOf(e.service.verify(clientId, policy(AttestationMode.enforce, withIos = false), jkt, "jti", None, iosAttestation(e, pair, "jti")))
        yield assertTrue(result == Some(Kind.Unsupported))
      },
      test("a value larger than an attestation is refused before it is decoded") {
        val pair = p256()
        for
          e <- env()
          oversized = evidence(Some("ios"), Some(b64(keyId(pair)))).copy(attestationObject = Some("A" * 30_000))
          result <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, oversized))
        yield assertTrue(result == Some(Kind.Malformed))
      },
    ),
    suite("Android")(
      test("a chain for the DPoP key, made for this client, is accepted with nothing remembered") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          result <- kindOf(e.service.verify(clientId, enforce, thumbprintOf(pair), "jti", None, evidence(Some("android"), chain = chain)))
          asked <- e.keys.asked.get
        yield assertTrue(result.isEmpty, asked == 0)
      },
      test("a chain made for another client, and a chain for another key, are refused") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          otherClient <- kindOf(e.service.verify("another-app", enforce, thumbprintOf(pair), "jti", None, evidence(Some("android"), chain = chain)))
          otherKey <- kindOf(e.service.verify(clientId, enforce, thumbprintOf(p256()), "jti", None, evidence(Some("android"), chain = chain)))
        yield assertTrue(otherClient == Some(Kind.Mismatch), otherKey == Some(Kind.Mismatch))
      },
      test("more certificates than a chain has are refused before any is decoded") {
        for
          e <- env()
          tooMany = AttestationEvidence(Some("android"), None, None, None, List.fill(7)("AAAA"))
          result <- e.service.verify(clientId, enforce, jkt, "jti", None, tooMany).either
        yield assertTrue(result.left.exists(f => f.kind == Kind.Malformed && f.detail.contains("more than")))
      },
      test("no chain, a client that accepts no Android build, and a value that is not base64, are refused") {
        val pair = p256()
        for
          e <- env()
          chain = androidChain(e.google, pair, AttestationService.androidChallenge(clientId))
          none <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, evidence(Some("android"))))
          iosOnly = policy(AttestationMode.enforce, withAndroid = false)
          unsupported <- kindOf(e.service.verify(clientId, iosOnly, thumbprintOf(pair), "jti", None, evidence(Some("android"), chain = chain)))
          garbage <- kindOf(e.service.verify(clientId, enforce, jkt, "jti", None, AttestationEvidence(Some("android"), None, None, None, List("***"))))
        yield assertTrue(none == Some(Kind.Absent), unsupported == Some(Kind.Unsupported), garbage == Some(Kind.Malformed))
      },
    ),
    suite("when the store the attestation needs is down")(
      test("report goes on, and enforce does not pretend the app was wrong: the request fails to be retried") {
        val pair = p256()
        for
          e <- env(fails = true)
          reported <- kindOf(e.service.verify(clientId, policy(AttestationMode.report), jkt, "jti", None, iosAttestation(e, pair, "jti")))
          enforced <- e.service.verify(clientId, enforce, jkt, "jti", None, iosAttestation(e, pair, "jti")).exit
        yield assertTrue(reported.isEmpty, enforced.isFailure, enforced.causeOption.exists(_.isDie))
      },
    ),
    suite("the outcome is counted per client, platform, mode and result")(
      test("two clients have series of their own: an outcome of one is not the other's") {
        for
          e <- env()
          a0 <- series("metrics-a", "none", "enforce", "Absent")
          b0 <- series("metrics-b", "none", "report", "Absent")
          _ <- ZIO.foreachDiscard(1 to 2)(_ => kindOf(e.service.verify("metrics-a", enforce, jkt, "jti", None, evidence())))
          _ <- kindOf(e.service.verify("metrics-b", policy(AttestationMode.report), jkt, "jti", None, evidence()))
          a <- series("metrics-a", "none", "enforce", "Absent")
          b <- series("metrics-b", "none", "report", "Absent")
          aElsewhere <- series("metrics-a", "none", "report", "Absent")
          bElsewhere <- series("metrics-b", "none", "enforce", "Absent")
        yield assertTrue(a - a0 == 2.0, b - b0 == 1.0, aElsewhere == 0.0, bElsewhere == 0.0)
      },
      test("a verified attestation is counted for its client") {
        val pair = p256()
        for
          e <- env()
          before <- series("metrics-d", "ios", "enforce", "verified")
          _ <- e.service.verify("metrics-d", enforce, jkt, "jti-m", None, iosAttestation(e, pair, "jti-m", "metrics-d"))
          after <- series("metrics-d", "ios", "enforce", "verified")
        yield assertTrue(after - before == 1.0)
      },
      test("a report that could not be checked is counted as Unavailable, for its client") {
        val pair = p256()
        for
          e <- env(fails = true)
          before <- series("metrics-e", "ios", "report", "Unavailable")
          _ <- e.service.verify("metrics-e", policy(AttestationMode.report), jkt, "jti-u", None, iosAttestation(e, pair, "jti-u", "metrics-e"))
          after <- series("metrics-e", "ios", "report", "Unavailable")
        yield assertTrue(after - before == 1.0)
      },
      test("a client whose mode is off is not counted at all") {
        for
          e <- env()
          _ <- e.service.verify("metrics-off", policy(AttestationMode.off), jkt, "jti", None, evidence(Some("ios"), Some("garbage")))
          counted <- ZIO.foreach(List("none", "ios", "android"))(platform =>
            ZIO.foreach(List("off", "report", "enforce"))(mode => series("metrics-off", platform, mode, "Malformed")),
          )
        yield assertTrue(counted.flatten.forall(_ == 0.0))
      },
      test("how long a verification took is recorded by platform, for a refusal as for an acceptance") {
        def durations(platform: String) =
          AttestationService.verificationDuration.tagged(MetricLabel("platform", platform)).value.map(_.count)
        val pair = p256()
        for
          e <- env()
          noneBefore <- durations("none")
          iosBefore <- durations("ios")
          _ <- kindOf(e.service.verify("metrics-f", enforce, jkt, "jti", None, evidence()))
          _ <- e.service.verify("metrics-f", enforce, jkt, "jti-f", None, iosAttestation(e, pair, "jti-f", "metrics-f"))
          noneAfter <- durations("none")
          iosAfter <- durations("ios")
        yield assertTrue(noneAfter - noneBefore >= 1L, iosAfter - iosBefore >= 1L)
      },
      test("a new App Attest key is counted for its client, and the same key attested again is not counted twice") {
        def registered(client: String) =
          Metric.counter("attestation_keys_registered_total").tagged(MetricLabel("client", client)).value.map(_.count)
        val pair = p256()
        for
          e <- env()
          before <- registered("metrics-g")
          _ <- e.service.verify("metrics-g", enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1", "metrics-g"))
          once <- registered("metrics-g")
          _ <- e.service.verify("metrics-g", enforce, jkt, "jti-2", None, iosAttestation(e, pair, "jti-2", "metrics-g"))
          again <- registered("metrics-g")
          other <- registered("metrics-h")
        yield assertTrue(once - before == 1.0, again == once, other == 0.0)
      },
      test("a platform the app made up is not a metric label, whatever it wrote") {
        for
          e <- env()
          _ <- ZIO.foreachDiscard(1 to 5)(i => kindOf(e.service.verify("metrics-c", enforce, jkt, "jti", None, evidence(Some(s"made-up-$i")))))
          made <- series("metrics-c", "made-up-1", "enforce", "Malformed")
          folded <- series("metrics-c", "none", "enforce", "Malformed")
        yield assertTrue(made == 0.0, folded >= 5.0)
      },
    ),
    suite("an app that says it could not attest")(
      test("under enforce it is refused, as each of the three reasons, and counted as its own outcome") {
        for
          e <- env()
          unsupported <- kindOf(e.service.verify("declared-a", enforce, jkt, "jti", None, evidence().copy(unavailable = Some("unsupported"))))
          down <- kindOf(e.service.verify("declared-a", enforce, jkt, "jti", None, evidence().copy(unavailable = Some("service_unavailable"))))
          unset <- kindOf(e.service.verify("declared-a", enforce, jkt, "jti", None, evidence().copy(unavailable = Some("not_configured"))))
          counted <- series("declared-a", "none", "enforce", "DeclaredServiceUnavailable")
        yield assertTrue(
          unsupported.contains(Kind.DeclaredUnsupported),
          down.contains(Kind.DeclaredServiceUnavailable),
          unset.contains(Kind.DeclaredNotConfigured),
          counted == 1.0,
          Kind.DeclaredServiceUnavailable.code == "declared_service_unavailable",
        )
      },
      test("a reason that is not one of the three is malformed, and under report nothing is refused") {
        for
          e <- env()
          made <- kindOf(e.service.verify("declared-b", enforce, jkt, "jti", None, evidence().copy(unavailable = Some("because"))))
          reported <- kindOf(e.service.verify("declared-b", policy(AttestationMode.report), jkt, "jti", None, evidence().copy(unavailable = Some("unsupported"))))
          counted <- series("declared-b", "none", "report", "DeclaredUnsupported")
        yield assertTrue(made.contains(Kind.Malformed), reported.isEmpty, counted == 1.0)
      },
      test("evidence that was sent is read as evidence: the declaration does not stand in for it") {
        for
          e <- env()
          result <- kindOf(e.service.verify("declared-c", enforce, jkt, "jti", None, evidence(Some("ios"), Some("garbage")).copy(unavailable = Some("unsupported"))))
        yield assertTrue(result.isDefined, !result.exists(_.toString.startsWith("Declared")))
      },
    ),
    test("the launch category Apple reports for a newly attested key is counted for its client, as a closed label") {
      val pair = p256()
      def categoryCount(client: String, label: String) =
        Metric.counter("attestation_ios_app_category_total")
          .tagged(Set(MetricLabel("client", client), MetricLabel("category", label))).value.map(_.count)
      for
        e <- env()
        sent = evidence(
          Some("ios"), Some(b64(keyId(pair))),
          attestation = Some(appleAttestation(e.chain, pair, appId, hashFor("jti-c", "category-client"), AppleOptions(category = Some(4)))),
        )
        before <- categoryCount("category-client", "app_store")
        _ <- e.service.verify("category-client", enforce, jkt, "jti-c", None, sent)
        after <- categoryCount("category-client", "app_store")
      yield assertTrue(after - before == 1.0)
    },
    test("a request under report waits for its check no longer than the bound, and the outcome is counted as a Timeout") {
      val slowTrust = new AttestationTrust:
        def appleRoots = apple().roots
        def androidRoots = google().roots
        def androidRevocation = ZIO.sleep(4.seconds).as((_: java.math.BigInteger) => false)
      val service = AttestationService.Impl(slowTrust, Keys(Ref.unsafe.make(Map.empty)(using zio.Unsafe), Ref.unsafe.make(0)(using zio.Unsafe), false), PlayIntegrity.unconfigured)
      for
        before <- series("report-slow", "android", "report", "Timeout")
        started <- Clock.nanoTime
        outcome <- service.verify(
          "report-slow", policy(AttestationMode.report), jkt, "jti", None,
          evidence(Some("android"), chain = List(Array.fill[Byte](10)(1))),
        ).either
        elapsed <- Clock.nanoTime.map(_ - started)
        after <- series("report-slow", "android", "report", "Timeout")
        // The check that was left behind is not counted once more when it ends (or is cut short): the request is a Timeout, once.
        _ <- ZIO.sleep(4500.millis)
        malformedBefore = 0.0
        malformed <- series("report-slow", "android", "report", "Malformed")
      yield assertTrue(outcome.isRight, elapsed < 3_900_000_000L, after - before == 1.0, malformed == malformedBefore)
    },
    test("the client data hash is bound to the client, the key and the proof") {
      val base = AttestationService.clientDataHash("a", "k", "j")
      assertTrue(
        !base.sameElements(AttestationService.clientDataHash("b", "k", "j")),
        !base.sameElements(AttestationService.clientDataHash("a", "l", "j")),
        !base.sameElements(AttestationService.clientDataHash("a", "k", "i")),
        !AttestationService.androidChallenge("a").sameElements(AttestationService.androidChallenge("b")),
        // The server's nonce, when the proof has one, is part of what is signed; a proof without one hashes as it always did.
        !base.sameElements(AttestationService.clientDataHash("a", "k", "j", Some("n"))),
        !AttestationService.clientDataHash("a", "k", "j", Some("n")).sameElements(AttestationService.clientDataHash("a", "k", "j", Some("m"))),
        base.sameElements(AttestationService.clientDataHash("a", "k", "j", None)),
        // An empty nonce is no nonce, as the SDKs read it.
        base.sameElements(AttestationService.clientDataHash("a", "k", "j", Some(""))),
      )
    },
    test("an assertion made ahead of time, before the server's nonce was known, is refused once the proof carries one") {
      val pair = p256()
      val aheadOfTime = AttestationService.clientDataHash(clientId, jkt, "jti-2")
      for
        e <- env()
        _ <- e.service.verify(clientId, enforce, jkt, "jti-1", None, iosAttestation(e, pair, "jti-1"))
        early <- kindOf(
          e.service.verify(
            clientId, enforce, jkt, "jti-2", Some("server-nonce"),
            evidence(Some("ios"), Some(b64(keyId(pair))), assertion = Some(appleAssertion(pair, appId, 1, aheadOfTime))),
          ),
        )
        sentWith = AttestationService.clientDataHash(clientId, jkt, "jti-3", Some("server-nonce"))
        late <- kindOf(
          e.service.verify(
            clientId, enforce, jkt, "jti-3", Some("server-nonce"),
            evidence(Some("ios"), Some(b64(keyId(pair))), assertion = Some(appleAssertion(pair, appId, 2, sentWith))),
          ),
        )
      yield assertTrue(early.isDefined, late.isEmpty)
    },
  ) @@ TestAspect.withLiveClock

  private def thumbprintOf(pair: KeyPair): String =
    ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build().computeThumbprint().toString
