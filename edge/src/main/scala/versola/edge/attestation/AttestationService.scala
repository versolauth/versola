package versola.edge.attestation

import versola.edge.attestation.AttestationCrypto.{malformed, sha256}
import versola.edge.attestation.AttestationFailure.Kind
import zio.metrics.{Metric, MetricLabel}
import zio.{Clock, IO, URLayer, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.util.Base64

/** What an app sent on `/native/start` as proof of what it is, as the form carried it. */
final case class AttestationEvidence(
    platform: Option[String],
    keyId: Option[String],
    attestationObject: Option[String],
    assertion: Option[String],
    chain: List[String],
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
      evidence: AttestationEvidence,
  ): IO[AttestationFailure, Unit]

object AttestationService:

  private val Domain = "versola-attest-v1"

  /** What an iOS app hands App Attest as its client data hash, so that an attestation or assertion is for one request of one client
    * by one DPoP key. The SDK builds the same bytes. */
  def clientDataHash(clientId: String, jkt: String, jti: String): Array[Byte] =
    sha256(s"$Domain\n$clientId\n$jkt\n$jti".getBytes(StandardCharsets.UTF_8))

  /** What an Android app gives the Keystore as the attestation challenge, so that a chain made for one client is not presented to
    * another. A server-chosen challenge would need a round trip before the key exists; the key's own possession, shown by the
    * proof, is what keeps a chain from being replayed. */
  def androidChallenge(clientId: String): Array[Byte] =
    sha256(s"$Domain\n$clientId".getBytes(StandardCharsets.UTF_8))

  /** Bounds on what is decoded, in characters of the form value: a real attestation object is about 5 KB, a certificate 1 KB. */
  private val MaxKeyIdLength = 128
  private val MaxAttestationLength = 24 * 1024
  private val MaxAssertionLength = 4 * 1024
  private val MaxCertificateLength = 12 * 1024

  private val Platforms = Set("ios", "android")

  private val requests = Metric.counter("attestation_requests_total")

  private def count(platform: String, mode: AttestationMode, result: String): UIO[Unit] =
    requests.tagged(Set(MetricLabel("platform", platform), MetricLabel("mode", mode.toString), MetricLabel("result", result))).increment

  val live: URLayer[AttestationTrust & AttestedKeyRepository, AttestationService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(trust: AttestationTrust, keys: AttestedKeyRepository) extends AttestationService:

    override def verify(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      if policy.mode == AttestationMode.off then ZIO.unit
      else
        // A metric label is a closed set: what the app wrote is not one, and every value it could choose would be a series of its own.
        val platform = evidence.platform.filter(Platforms.contains).getOrElse("none")
        val checked = run(clientId, policy, jkt, jti, evidence).tapBoth(
          failure => count(platform, policy.mode, failure.kind.toString),
          _ => count(platform, policy.mode, "verified"),
        )
        policy.mode match
          case AttestationMode.enforce => checked
          // A report is only ever a report: a refusal is counted and logged above, and a fault of the store the attestation needs
          // (a defect here, a failed request under `enforce`, which the caller can retry) is no reason to fail the request either.
          case _ =>
            checked.catchAll(_ => ZIO.unit).catchAllDefect: error =>
              count(platform, policy.mode, "Unavailable") *> ZIO.logWarning(s"attestation of client '$clientId' could not be checked: $error")

    private def run(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      evidence.platform match
        case None => ZIO.fail(AttestationFailure(Kind.Absent, "the app sent no attestation"))
        case Some("ios") => ios(clientId, policy, jkt, jti, evidence)
        case Some("android") => android(clientId, policy, jkt, evidence)
        case Some(_) => ZIO.fail(malformed("attestation_platform is ios or android"))

    private def ios(
        clientId: String,
        policy: AttestationPolicy,
        jkt: String,
        jti: String,
        evidence: AttestationEvidence,
    ): IO[AttestationFailure, Unit] =
      for
        apps <- ZIO.fromOption(policy.ios.filter(_.appIds.nonEmpty))
          .orElseFail(AttestationFailure(Kind.Unsupported, "this client accepts no iOS build"))
        keyId <- ZIO.fromOption(evidence.keyId).orElseFail(malformed("attestation_key_id is required"))
        keyBytes <- ZIO.fromEither(decode(keyId, MaxKeyIdLength))
        // One form for the key id however the app wrote it, so that the same key is the same row.
        key = Base64.getUrlEncoder.withoutPadding.encodeToString(keyBytes)
        clientData = clientDataHash(clientId, jkt, jti)
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
        _ <- ZIO.fromEither(AndroidKeyAttestation.verify(chain, androidChallenge(clientId), policy.android, jkt, trust.androidRoots, revoked, now))
      yield ()

    /** Standard or URL-safe base64, with or without padding: what a platform's own encoder produced. */
    private def decode(value: String, maxLength: Int): Either[AttestationFailure, Array[Byte]] =
      if value.length > maxLength then Left(malformed("a value is larger than an attestation is"))
      else
        val normalized = value.trim.replace('-', '+').replace('_', '/')
        scala.util.Try(Base64.getDecoder.decode(normalized + "=" * ((4 - normalized.length % 4) % 4))).toEither
          .left.map(_ => malformed("a value is not base64"))
