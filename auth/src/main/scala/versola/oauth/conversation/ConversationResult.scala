package versola.oauth.conversation

import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.model.AuthorizationCode
import versola.oauth.session.model.SessionId
import versola.util.Base64Url

sealed trait ConversationResult

object ConversationResult:
  sealed trait Render extends ConversationResult
  sealed trait Decision extends ConversationResult

  case object IllegalState extends Render

  case object NotFound extends Render

  case object LimitsExceeded extends Render

  case class RenderStep(step: ConversationStep) extends Render

  case class Complete(
      code: AuthorizationCode,
      sessionId: SessionId
  ) extends Render

  case class StepPassed(step: ConversationStep) extends Decision

sealed trait Directive

object Directive:
  case class SetConversationCookie(authId: AuthId) extends Directive
