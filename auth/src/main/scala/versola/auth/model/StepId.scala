package versola.auth.model

sealed trait StepId

object StepId:
  case object Credential extends StepId
  case object Otp extends StepId
  case object Password extends StepId