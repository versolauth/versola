package versola.antifraud

import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.util.{Email, Phone}
import zio.UIO

object RecordingAntifraudEvents:
  case class Step(
      action: AntifraudAction,
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Option[Decision],
  )

/** An [[AntifraudEvents]] that keeps what the sign-in steps told it, for the specs of those steps. */
final class RecordingAntifraudEvents(
    smsAllowed: Boolean = true,
    afterRequested: UIO[Unit] = zio.ZIO.unit,
    decision: Decision = Decision.allow,
    fakeDelay: zio.Duration = zio.Duration.Zero,
    smsWouldRefuse: Boolean = false,
) extends AntifraudEvents:
  import RecordingAntifraudEvents.Step

  private val told = scala.collection.mutable.ListBuffer.empty[Step]
  private val kindsTold = scala.collection.mutable.ListBuffer.empty[(String, AntifraudOutcome)]

  /** The kind and outcome of each [[challengeStep]], in order. */
  def kinds: List[(String, AntifraudOutcome)] = synchronized(kindsTold.toList)

  def steps: List[Step] = synchronized(told.toList)

  override def record(event: AntifraudEvent): UIO[Unit] = zio.ZIO.unit

  override def subjectHash(credential: Either[Email, Phone]): zio.Task[String] =
    zio.ZIO.succeed("hash:" + credential.fold(identity[String], identity[String]))

  private val asked = scala.collection.mutable.ListBuffer.empty[Boolean]
  private val released = new java.util.concurrent.atomic.AtomicInteger(0)

  /** The `charge` flag of each [[allowSms]] call, in order. */
  def smsAsked: List[Boolean] = synchronized(asked.toList)

  def smsReleased: Int = released.get

  override def allowSms(clientId: ClientId, phone: Phone, at: java.time.Instant, charge: Boolean): UIO[SmsBudget.Decision] =
    zio.ZIO.succeed(synchronized(asked += charge)) *> zio.ZIO.succeed:
      if !smsAllowed then SmsBudget.Decision.Refused
      else if smsWouldRefuse then SmsBudget.Decision.WouldRefuse
      else SmsBudget.Decision.Allowed

  private val took = scala.collection.mutable.ListBuffer.empty[zio.Duration]

  /** The durations of the real sends told to [[sendTook]], in order. */
  def sends: List[zio.Duration] = synchronized(took.toList)

  override def sendTook(duration: zio.Duration): UIO[Unit] = zio.ZIO.succeed(synchronized(took += duration)).unit

  override def fakeSendDelay: UIO[zio.Duration] = zio.ZIO.succeed(fakeDelay)

  override def releaseSms(clientId: ClientId, phone: Phone, at: java.time.Instant): UIO[Unit] = zio.ZIO.succeed(released.incrementAndGet()).unit

  private val decided = scala.collection.mutable.ListBuffer.empty[Boolean]

  /** The `repeated` flag of each [[decideOtpRequest]] call, in order. */
  def decisions: List[Boolean] = synchronized(decided.toList)

  override def decideOtpRequest(clientId: ClientId, userAgent: Option[String], credential: Either[Email, Phone], repeated: Boolean): UIO[Decision] =
    zio.ZIO.succeed(synchronized(decided += repeated)) *> zio.ZIO.succeed(decision)

  override def otpRequested(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Option[Decision],
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized(told += Step(
      AntifraudAction.otp_request,
      authId,
      clientId,
      userAgent,
      credential,
      outcome,
      decision,
    ))) *> afterRequested

  override def challengeStep(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Decision,
      kind: String,
      level: Option[Int],
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized {
      kindsTold += kind -> outcome
      told += Step(AntifraudAction.otp_request, authId, clientId, userAgent, credential, outcome, Some(decision))
    }).unit

  override def otpChecked(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized(told += Step(AntifraudAction.otp_verify, authId, clientId, userAgent, credential, outcome, None))).unit
