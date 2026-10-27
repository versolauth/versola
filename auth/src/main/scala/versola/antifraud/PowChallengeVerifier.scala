package versola.antifraud

import versola.util.{Base64, CoreConfig, Secret, SecureRandom, SecurityService}
import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.util.Try

/** Proof of work as a [[ChallengeVerifier]]: the client has to find a `nonce` for which SHA-256 of `seed:nonce` starts
  * with `difficulty` zero bits. It costs a browser a moment and a script that asks for thousands of codes a great deal
  * of CPU, which is the point; the work grows with the score of the request, so a request that looks worse is made
  * to pay more.
  *
  * The token is signed and says whom it is for: `<payload>.<mac>`, both base64url, the payload carrying the tenant,
  * client, conversation, the hash of the destination, the expiry, the difficulty and the seed. Checking it needs only
  * the key, so any instance can check what another issued. The seed is a random value of its own, not the token, so
  * that the client hashes one short block.
  */
object PowChallengeVerifier:

  val Kind = "pow"

  /** @param enabled       whether a `Challenge` decision is put to the client at all; off, it stays a pause
    * @param minDifficulty zero bits asked of a request that only just reached the challenge score
    * @param maxDifficulty the most zero bits asked of any request: each bit doubles the work, and a phone has to do it
    * @param scoreStep     the score above the challenge threshold that adds one bit
    * @param ttl           how long a challenge may be solved
    * @param maxAttempts   wrong solutions allowed before the request is refused
    */
  case class Settings(
      enabled: Boolean = true,
      minDifficulty: Int = 14,
      maxDifficulty: Int = 18,
      scoreStep: Int = 15,
      ttl: Duration = 2.minutes,
      maxAttempts: Int = 5,
  )

  object Settings:
    val default: Settings = Settings()

  /** What the signature covers. */
  private case class Payload(
      tenant: Option[String],
      client: String,
      conversation: String,
      generation: Long,
      attempts: Int,
      subject: String,
      expires: Long,
      difficulty: Int,
      seed: String,
  ) derives JsonCodec

  /** The zero bits asked at `score` when the engine challenges from `challengeAt`. */
  def difficultyFor(settings: Settings, challengeAt: Int, score: Int): Int =
    val extra = if settings.scoreStep <= 0 then 0 else math.max(0, score - challengeAt) / settings.scoreStep
    math.min(settings.maxDifficulty, settings.minDifficulty + extra)

  def leadingZeroBits(digest: Array[Byte]): Int =
    var bits = 0
    var i = 0
    while i < digest.length do
      val b = digest(i) & 0xff
      if b == 0 then
        bits += 8
        i += 1
      else
        bits += Integer.numberOfLeadingZeros(b) - 24
        i = digest.length
    bits

  /** The nonce is a short decimal number: whatever else a client sent is not a solution. */
  private val NonceLength = 20

  val live: ZLayer[Settings & AntifraudEvents.Settings & SecurityService & SecureRandom & CoreConfig, Nothing, ChallengeVerifier] =
    ZLayer.fromZIO:
      for
        settings <- ZIO.service[Settings]
        events <- ZIO.service[AntifraudEvents.Settings]
        securityService <- ZIO.service[SecurityService]
        secureRandom <- ZIO.service[SecureRandom]
        config <- ZIO.service[CoreConfig]
        rootKey = config.security.antifraudSubjectSecret.getOrElse(config.security.sessionsSecret)
        // A label of its own: a token made with this key cannot be mistaken for any other MAC made with the same secret.
        key <- securityService.mac(Secret("versola/antifraud/challenge/pow/v1".getBytes(StandardCharsets.UTF_8)), rootKey).map(Secret(_)).orDie
      yield
        if settings.enabled then Impl(settings, events.decision.challengeAt, securityService, secureRandom, key): ChallengeVerifier
        else ChallengeVerifier.off

  class Impl(
      settings: Settings,
      challengeAt: Int,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      key: Secret,
  ) extends ChallengeVerifier:

    override def enabled: Boolean = true

    override def maxAttempts: Int = settings.maxAttempts

    override def issue(subject: ChallengeSubject, score: Int, attempts: Int): UIO[Option[Challenge]] =
      (for
        now <- Clock.instant
        seed <- secureRandom.nextBytes(16).map(Base64.urlEncode)
        difficulty = difficultyFor(settings, challengeAt, score)
        payload = Payload(
          tenant = subject.tenantId.map(_.toString),
          client = subject.clientId.toString,
          conversation = subject.conversationId.toString,
          generation = subject.generation,
          attempts = attempts,
          subject = subject.subjectHash,
          expires = now.plusSeconds(settings.ttl.toSeconds).getEpochSecond,
          difficulty = difficulty,
          seed = seed,
        )
        body = payload.toJson.getBytes(StandardCharsets.UTF_8)
        mac <- securityService.mac(Secret(body), key)
      yield Some(
        Challenge(
          kind = Kind,
          token = s"${Base64.urlEncode(body)}.${Base64.urlEncode(mac)}",
          level = difficulty,
          params = Map("seed" -> seed, "difficulty" -> difficulty.toString),
        ),
      )).catchAllCause(cause => ZIO.logDebugCause("could not issue a proof-of-work challenge", cause).as(None))

    override def verify(subject: ChallengeSubject, token: String, solution: String): UIO[ChallengeResult] =
      (for
        now <- Clock.instant
        result <- parse(token) match
          case None => ZIO.succeed(ChallengeResult.Failed(1))
          case Some((body, signature, payload)) =>
            securityService.mac(Secret(body), key).map: expected =>
              if !MessageDigest.isEqual(expected, signature) then ChallengeResult.Failed(1)
              else if !Instant.ofEpochSecond(payload.expires).isAfter(now) then ChallengeResult.Expired
              else if !boundTo(payload, subject) then ChallengeResult.Failed(1)
              else if !solves(payload, solution) then ChallengeResult.Failed(payload.attempts + 1)
              else ChallengeResult.Passed
      yield result).catchAllCause(cause =>
        ZIO.logDebugCause("could not check a proof-of-work solution", cause).as(ChallengeResult.Failed(1)),
      )

    private def parse(token: String): Option[(Array[Byte], Array[Byte], Payload)] =
      token.split('.') match
        case Array(encodedBody, encodedSignature) =>
          (for
            body <- Try(Base64.urlDecode(encodedBody)).toOption
            signature <- Try(Base64.urlDecode(encodedSignature)).toOption
            payload <- new String(body, StandardCharsets.UTF_8).fromJson[Payload].toOption
          yield (body, signature, payload))
        case _ => None

    private def boundTo(payload: Payload, subject: ChallengeSubject): Boolean =
      payload.tenant == subject.tenantId.map(_.toString) &&
        payload.client == subject.clientId.toString &&
        payload.conversation == subject.conversationId.toString &&
        payload.generation == subject.generation &&
        payload.subject == subject.subjectHash

    private def solves(payload: Payload, solution: String): Boolean =
      solution.nonEmpty && solution.length <= NonceLength && solution.forall(_.isDigit) && {
        val digest = MessageDigest.getInstance("SHA-256").digest(s"${payload.seed}:$solution".getBytes(StandardCharsets.UTF_8))
        leadingZeroBits(digest) >= payload.difficulty
      }
