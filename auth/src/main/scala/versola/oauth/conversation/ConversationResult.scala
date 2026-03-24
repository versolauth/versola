package versola.oauth.conversation

import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.model.{AuthorizationCode, State}
import versola.oauth.session.model.SessionId
import versola.user.model.UserId
import versola.util.{Base64Url, MAC}
import zio.http.URL
import zio.json.ast.Json

sealed trait ConversationResult

object ConversationResult:
  sealed trait Render extends ConversationResult
  sealed trait Decision extends ConversationResult

  case object IllegalState extends Render

  case object NotFound extends Render

  case object LimitsExceeded extends Render

  case class RenderStep(step: ConversationStep) extends Render

  case class IdTokenData(
      userId: UserId,
      claims: Map[String, Json],
      clientId: ClientId,
  )

  case class Complete(
      redirectUri: URL,
      state: Option[State],
      code: AuthorizationCode,
      sessionId: MAC.Of[SessionId],
      idTokenData: Option[IdTokenData],
  ) extends Render

  case class StepPassed(step: ConversationStep) extends Decision

sealed trait Directive

object Directive:
  case class SetConversationCookie(authId: AuthId) extends Directive
