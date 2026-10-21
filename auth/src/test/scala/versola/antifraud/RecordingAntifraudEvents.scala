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
  )

/** An [[AntifraudEvents]] that keeps what the sign-in steps told it, for the specs of those steps. */
final class RecordingAntifraudEvents(smsAllowed: Boolean = true) extends AntifraudEvents:
  import RecordingAntifraudEvents.Step

  private val told = scala.collection.mutable.ListBuffer.empty[Step]

  def steps: List[Step] = synchronized(told.toList)

  override def record(event: AntifraudEvent): UIO[Unit] = zio.ZIO.unit

  override def allowSms(clientId: ClientId, phone: Phone): UIO[Boolean] = zio.ZIO.succeed(smsAllowed)

  override def otpRequested(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized(told += Step(AntifraudAction.otp_request, authId, clientId, userAgent, credential, outcome))).unit

  override def otpChecked(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit] =
    zio.ZIO.succeed(synchronized(told += Step(AntifraudAction.otp_verify, authId, clientId, userAgent, credential, outcome))).unit
