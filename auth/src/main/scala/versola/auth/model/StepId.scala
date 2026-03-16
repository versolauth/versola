package versola.auth.model

sealed trait StepId

object StepId:
  case object Empty extends StepId
  case object Otp extends StepId
  case object Password extends StepId