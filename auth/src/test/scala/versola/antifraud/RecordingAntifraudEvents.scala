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
) extends AntifraudEvents:
  import RecordingAntifraudEvents.Step

  private val told = scala.collection.mutable.ListBuffer.empty[Step]

  def steps: List[Step] = synchronized(told.toList)

  override def record(event: AntifraudEvent): UIO[Unit] = zio.ZIO.unit

  override def subjectHash(credential: Either[Email, Phone]): zio.Task[String] =
    zio.ZIO.succeed("hash:" + credential.fold(identity[String], identity[String]))

  private val asked = scala.collection.mutable.ListBuffer.empty[Boolean]
  private val released = new java.util.concurrent.atomic.AtomicInteger(0)

  /** The `charge` flag of each [[allowSms]] call, in order. */
  def smsAsked: List[Boolean] = synchronized(asked.toList)

  def smsReleased: Int = released.get

  override def allowSms(clientId: ClientId, phone: Phone, at: java.time.Instant, charge: Boolean): UIO[Boolean] =
    zio.ZIO.succeed(synchronized(asked += charge)) *> zio.ZIO.succeed(smsAllowed)

  override def releaseSms(clientId: ClientId, phone: Phone, at: java.time.Instant): UIO[Unit] = zio.ZIO.succeed(released.incrementAndGet()).unit

  private val held = new java.util.concurrent.atomic.AtomicInteger(0)

  /** How many times a decision asked to hold a request back. */
  def holds: Int = held.get

  override def decideOtpRequest(clientId: ClientId, userAgent: Option[String], credential: Either[Email, Phone]): UIO[Decision] =
    zio.ZIO.succeed(decision)

  override def hold(decision: Decision): UIO[Unit] = zio.ZIO.succeed(held.incrementAndGet()).unit

  override def sendTook(took: zio.Duration): UIO[Unit] = zio.ZIO.unit

  override def fakeSendDelay: UIO[zio.Duration] = zio.ZIO.succeed(fakeDelay)

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

  override def otpChecked(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized(told += Step(AntifraudAction.otp_verify, authId, clientId, userAgent, credential, outcome, None))).unit
