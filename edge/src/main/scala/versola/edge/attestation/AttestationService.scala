package versola.edge.attestation

import versola.edge.attestation.AttestationCrypto.{malformed, sha256}
import versola.edge.attestation.AttestationFailure.Kind
import zio.metrics.{Metric, MetricLabel}
import zio.{Clock, IO, Ref, URLayer, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.util.Base64

/** What an app sent on `/native/start` as proof of what it is, as the form carried it. */
final case class AttestationEvidence(
    platform: Option[String],
    keyId: Option[String],
    attestationObject: Option[String],
    assertion: Option[String],
    chain: List[String],
    /** Android, when the policy of the app asks for it: the Play Integrity token, as the Play Integrity API returned it. */
    integrityToken: Option[String] = None,
    /** Why the app could not attest, when it says so instead of sending evidence: one of [[AttestationService.UnavailableReasons]]. */
    unavailable: Option[String] = None,
)

/** App attestation of `/native/start` (#423): what the policy of the client asks, what the app sent, and the outcome.
  *
  * The request is tied to the DPoP key, which is the key every token the flow issues is bound to. On Android the attested key *is*
  * the DPoP key, so the certificate's public key has the proof's `jkt`. On iOS App Attest signs only what it is handed, so the app
  * hands it a hash of the request's own proof -- its key and its `jti`, which the replay guard has already made single-use -- and an
  * assertion is good for exactly that proof.
  */
trait AttestationService:

  /** Fails with the reason the attestation was not accepted, when the policy refuses a request over it. `report` never fails: it
    * verifies, counts and answers as if it had not; `off` does not look. */
  def verify(
      clientId: String,
      policy: AttestationPolicy,
      jkt: String,
      jti: String,
      nonce: Option[String],
      evidence: AttestationEvidence,
  ): IO[AttestationFailure, Unit]

object AttestationService:

  private val Domain = "versola-attest-v1"

  /** What an iOS app hands App Attest as its client data hash, so that an attestation or assertion is for one request of one client
    * by one DPoP key. The SDK builds the same bytes.
    *
    * `nonce` is the server nonce of the proof, when the proof carries one (RFC 9449 §8: this edge asked for it): it is the one value in
    * what the app signs that the server chose, so an assertion cannot be made ahead of time for a `jti` the app picks. A proof without
    * a nonce hashes as it always did, so a client that does not ask for nonces is unchanged. */
  def clientDataHash(clientId: String, jkt: String, jti: String, nonce: Option[String] = None): Array[Byte] =
    sha256(s"$Domain\n$clientId\n$jkt\n$jti${nonce.filter(_.nonEmpty).fold("")("\n" + _)}".getBytes(StandardCharsets.UTF_8))

  /** What an Android app gives the Keystore as the attestation challenge, so that a chain made for one client is not presented to
    * another.
    *
    * A deliberate deviation from Google's advice of a server-provided challenge ("reuse allows replay of older certificates"). The
    * challenge is fixed when the Keystore makes the key, which is before any round trip to this edge; and what replay would need is
    * the hardware key itself, whose possession the DPoP proof shows on every request: the attested key is the proof's key. A chain
    * therefore does nothing in the hands of anyone else, which two specs hold: a chain made for another client's challenge is refused
    * (`AndroidKeyAttestationSpec`, "a chain made for another client"), and so is the same chain with a proof by another key
    * (`AndroidKeyAttestationSpec`, "a key that is not the DPoP key of the request"). An app made before the SDK that sets this
    * challenge cannot pass whatever chain it has, and takes a new key at its next `/native/start` (see `edge.yaml`). */
  def androidChallenge(clientId: String): Array[Byte] =
    sha256(s"$Domain\n$clientId".getBytes(StandardCharsets.UTF_8))

  /** Bounds on what is decoded, in characters of the form value: a real attestation object is about 5 KB, a certificate 1 KB. */
  private val MaxKeyIdLength = 128
  private val MaxAttestationLength = 24 * 1024
  private val MaxAssertionLength = 4 * 1024
  private val MaxCertificateLength = 12 * 1024

  private val Platforms = Set("ios", "android")

  /** How long a request under `report` waits for its attestation to be checked. */
  private val ReportBound = 2.seconds

  /** Why an app that could not attest says so (`attestation_unavailable`): a closed set, so that the metric can tell an old device from
    * Apple's service being down, which "the app sent nothing" cannot. Under `enforce` such a request is still refused. */
  val UnavailableReasons: Map[String, Kind] = Map(
    "unsupported" -> Kind.DeclaredUnsupported,
    "service_unavailable" -> Kind.DeclaredServiceUnavailable,
    "not_configured" -> Kind.DeclaredNotConfigured,
  )

  private val requests = Metric.counter("attestation_requests_total")

  /** The launch category Apple reports for an attested iOS key (`apple_validation_category_01`): App Store, TestFlight, development,
    * enterprise or ad hoc. Counted, not enforced: the values are from Apple's documentation and have not been read from a device here,
    * so what they show under `report` is what decides whether a policy may refuse by category. A closed set per `client`. */
  private val appCategories = Metric.counter("attestation_ios_app_category_total")

  /** `client` is the client the request is for, which is what lets an operator see whose installs fail under `report` before moving
    * that client to `enforce`. It is a closed set: `verify` is only called for a client central holds a policy for, and the id is
    * the one the request's path names and edge looked the policy up by, never something the app wrote in the form. */
  private def count(client: String, platform: String, mode: AttestationMode, result: String): UIO[Unit] =
    requests.tagged(
      Set(MetricLabel("client", client), MetricLabel("platform", platform), MetricLabel("mode", mode.toString), MetricLabel("result", result)),
    ).increment

  /** What an Android app hands Play Integrity as its nonce, so that a verdict is for one request of one client by one DPoP key: the
    * client data hash, in the URL-safe base64 without padding that Play Integrity requires of a nonce. */
  def integrityNonce(clientId: String, jkt: String, jti: String, nonce: Option[String] = None): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(clientDataHash(clientId, jkt, jti, nonce))

  private val MaxIntegrityTokenLength = 24 * 1024

  val live: URLayer[AttestationTrust & AttestedKeyRepository & PlayIntegrity, AttestationService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(trust: AttestationTrust, keys: AttestedKeyRepository, integrity: PlayIntegrity) extends AttestationService:

    override def verify(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        nonce: Option[String],
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      if policy.mode == AttestationMode.off then ZIO.unit
      else
        // A metric label is a closed set: what the app wrote is not one, and every value it could choose would be a series of its own.
        val platform = evidence.platform.filter(Platforms.contains).getOrElse("none")
        // A request under `report` that was given up on (see below) is counted as `Timeout` and not again by the check that was left behind.
        Ref.make(false).flatMap: gaveUp =>
          def counted(result: String) = gaveUp.get.flatMap(given_ => count(clientId, platform, policy.mode, result).unless(given_))
          val checked = run(clientId, policy, jkt, jti, nonce, evidence).tapBoth(failure => counted(failure.kind.toString), _ => counted("verified"))
          decide(clientId, policy, platform, checked, gaveUp)

    private def decide(
        clientId: String,
        policy: AttestationPolicy,
        platform: String,
        checked: IO[AttestationFailure, Unit],
        gaveUp: Ref[Boolean],
    ): IO[AttestationFailure, Unit] =
      policy.mode match
        case AttestationMode.enforce => checked
        // A report is only ever a report: a refusal is counted and logged above, and a fault of the store the attestation needs
        // (a defect here, a failed request under `enforce`, which the caller can retry) is no reason to fail the request either.
        case _ =>
          val quietly = checked.catchAll(_ => ZIO.unit).catchAllDefect: error =>
            count(clientId, platform, policy.mode, "Unavailable") *> ZIO.logWarning(s"attestation of client '$clientId' could not be checked: $error")
          // A mode that promises to change nothing must not slow the sign-in either: the first Android request can wait for Google's
          // revocation list, and a Play Integrity decode for Google. The caller waits for `ReportBound` at most; past it the check is
          // given up (not waited for), the request goes on, and the outcome is counted as `Timeout`. Interrupting is safe for the list
          // of Google's: its first fetch does not run in the request that asked for it (see `AttestationTrust.make`).
          quietly.disconnect.timeout(ReportBound).flatMap:
            case Some(_) => ZIO.unit
            case None =>
              gaveUp.set(true) *> count(clientId, platform, policy.mode, "Timeout") *>
                ZIO.logWarning(s"attestation of client '$clientId' took longer than $ReportBound under report: the request goes on")

    private def run(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        nonce: Option[String],
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      val sentMaterial = evidence.keyId.isDefined || evidence.attestationObject.isDefined || evidence.assertion.isDefined || evidence.chain.nonEmpty
      evidence.unavailable.filter(_ => !sentMaterial) match
        case Some(reason) =>
          ZIO.fail(UnavailableReasons.get(reason).fold(malformed("attestation_unavailable is unsupported, service_unavailable or not_configured"))(
            AttestationFailure(_, s"the app could not attest: $reason"),
          ))
        case None => attestationOf(clientId, policy, jkt, jti, nonce, evidence)

    private def attestationOf(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        nonce: Option[String],
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      evidence.platform match
        case None => ZIO.fail(AttestationFailure(Kind.Absent, "the app sent no attestation"))
        case Some("ios") => ios(clientId, policy, jkt, jti, nonce, evidence)
        case Some("android") => android(clientId, policy, jkt, jti, nonce, evidence)
        case Some(_) => ZIO.fail(malformed("attestation_platform is ios or android"))

    private def ios(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        nonce: Option[String],
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      for
        apps <- ZIO.fromOption(policy.ios.filter(_.appIds.nonEmpty))
          .orElseFail(AttestationFailure(Kind.Unsupported, "this client accepts no iOS build"))
        keyId <- ZIO.fromOption(evidence.keyId).orElseFail(malformed("attestation_key_id is required"))
        keyBytes <- ZIO.fromEither(decode(keyId, MaxKeyIdLength))
        // One form for the key id however the app wrote it, so that the same key is the same row.
        key = Base64.getUrlEncoder.withoutPadding.encodeToString(keyBytes)
        clientData = clientDataHash(clientId, jkt, jti, nonce)
        now <- Clock.instant
        _ <- (evidence.attestationObject, evidence.assertion) match
          case (Some(_), Some(_)) => ZIO.fail(malformed("send an attestation or an assertion, not both"))
          case (Some(attestation), None) =>
            for
              decoded <- ZIO.fromEither(decode(attestation, MaxAttestationLength))
              attested <- ZIO.fromEither(
                AppAttest.verifyAttestation(decoded, keyBytes, clientData, apps.appIds, apps.environment, trust.appleRoots, now),
              )
              _ <- keys.register(clientId, key, attested.publicKey, apps.environment, now).orDie
              _ <- appCategories.tagged(Set(MetricLabel("client", clientId), MetricLabel("category", AppAttest.Category.label(attested.category)))).increment
            yield ()
          case (None, Some(assertion)) =>
            for
              decoded <- ZIO.fromEither(decode(assertion, MaxAssertionLength))
              stored <- keys.find(clientId, key).orDie
                .someOrFail(AttestationFailure(Kind.UnknownKey, "this key has not been attested: send the attestation object"))
              // The environment is part of what the key was attested as, and the policy may have changed since.
              _ <- ZIO.fail(AttestationFailure(Kind.DeviceNotAccepted, s"the key was attested in the ${stored.environment} environment"))
                .unless(stored.environment == apps.environment)
              counter <- ZIO.fromEither(AppAttest.verifyAssertion(decoded, clientData, stored.publicKey, apps.appIds))
              moved <- keys.advance(clientId, key, counter, now).orDie
              _ <- ZIO.fail(AttestationFailure(Kind.Replay, "the assertion's counter has not moved forward")).unless(moved)
            yield ()
          case (None, None) => ZIO.fail(AttestationFailure(Kind.Absent, "the app sent neither an attestation nor an assertion"))
      yield ()

    private def android(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        nonce: Option[String],
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      for
        _ <- ZIO.fail(AttestationFailure(Kind.Unsupported, "this client accepts no Android build")).when(policy.android.isEmpty)
        _ <- ZIO.fail(AttestationFailure(Kind.Absent, "attestation_chain is required")).when(evidence.chain.isEmpty)
        // Counted before anything is decoded: the form can carry as many values as the body limit lets through.
        _ <- ZIO.fail(malformed(s"the certificate chain has more than ${AttestationCrypto.MaxChainLength} certificates"))
          .when(evidence.chain.sizeIs > AttestationCrypto.MaxChainLength)
        chain <- ZIO.fromEither(evidence.chain.foldRight[Either[AttestationFailure, List[Array[Byte]]]](Right(Nil))((encoded, rest) =>
          for tail <- rest; head <- decode(encoded, MaxCertificateLength) yield head :: tail,
        ))
        now <- Clock.instant
        revoked <- trust.androidRevocation
        verified <- ZIO.fromEither(AndroidKeyAttestation.verify(chain, androidChallenge(clientId), policy.android, jkt, trust.androidRoots, revoked, now))
        // The key attestation says which build this is; whether that build also needs the Play Store's word is the policy's, per build.
        _ <- ZIO.when(verified.app.playIntegrity):
          for
            token <- ZIO.fromOption(evidence.integrityToken)
              .orElseFail(AttestationFailure(Kind.Absent, "attestation_integrity_token is required"))
            _ <- ZIO.fail(malformed("a value is larger than an integrity token is")).when(token.length > MaxIntegrityTokenLength)
            _ <- integrity.verify(verified.app, token, integrityNonce(clientId, jkt, jti, nonce), now)
          yield ()
      yield ()

    /** Standard or URL-safe base64, with or without padding: what a platform's own encoder produced. */
    private def decode(value: String, maxLength: Int): Either[AttestationFailure, Array[Byte]] =
      if value.length > maxLength then Left(malformed("a value is larger than an attestation is"))
      else
        val normalized = value.trim.replace('-', '+').replace('_', '/')
        scala.util.Try(Base64.getDecoder.decode(normalized + "=" * ((4 - normalized.length % 4) % 4))).toEither
          .left.map(_ => malformed("a value is not base64"))
