package versola.auth.model

sealed trait StepId

object StepId:
  case object Credential extends StepId
  case object Otp extends StepId
  case object Password extends StepId
  case object SetPassword extends StepId
  case object PasskeyEnroll extends StepId
  case object AccessDenied extends StepId