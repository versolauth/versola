package versola.oauth.conversation

import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{AuthorizationCode, State, UserAgentData}
import versola.oauth.session.model.{PublicSessionId, SessionId, UserAgentId}
import versola.user.model.UserId
import versola.util.Base64Url
import zio.http.URL
import zio.json.ast.Json

sealed trait ConversationResult

object ConversationResult:
  sealed trait Render extends ConversationResult
  sealed trait Decision extends ConversationResult

  /** A submission didn't match the conversation's current step, e.g. a stale form (browser
    * back/forward, double submit) or a tampered request. */
  case object BadRequest extends Render

  /** Lost an optimistic-concurrency race writing the conversation, e.g. a double-clicked or
    * duplicate-tab submission. Not a bug: the losing write is simply dropped in favor of
    * whichever submission won. */
  case object WriteConflict extends Render

  case object ServiceUnavailable extends Render

  case class RenderStep(step: ConversationStep) extends Render

  case class IdTokenData(
      userId: UserId,
      claims: Map[String, Json],
      clientId: ClientId,
      sessionId: PublicSessionId,
  )

  case class Complete(
      redirectUri: URL,
      state: Option[State],
      code: AuthorizationCode,
      sessionId: SessionId,
      idTokenData: Option[IdTokenData],
      userAgentId: UserAgentId,
      userAgentData: UserAgentData,
  ) extends Render

  case class StepPassed(record: ConversationRecord) extends Decision

sealed trait Directive

object Directive:
  case class SetConversationCookie(authId: AuthId) extends Directive
