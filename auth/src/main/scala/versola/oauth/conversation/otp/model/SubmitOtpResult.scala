package versola.oauth.conversation.otp.model

sealed trait SubmitOtpResult

object SubmitOtpResult:
  case object Failure extends SubmitOtpResult
  case object Success extends SubmitOtpResult
  case object LimitsExceeded extends SubmitOtpResult
