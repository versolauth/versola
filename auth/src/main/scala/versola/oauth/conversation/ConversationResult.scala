package versola.oauth.conversation

import versola.antifraud.Challenge
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{AuthorizationCode, State, UserAgentData}
import versola.oauth.session.model.{PublicSessionId, SessionId, UserAgentId}
import versola.user.model.UserId
import versola.util.{Base64Url, RoutingKey}
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

  /** The request needs a proof before a code is sent: nothing was sent, and the conversation stays where it was. Only a
    * submit sent from script is answered with one; any other that would have been challenged is refused. */
  case class Challenged(challenge: Challenge) extends Render

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

  /** The user of the address typed is held by another cell: the browser is sent there with the sign-in sealed
    * (`CellHandoff`, versolauth/versola#1046). This cell's conversation is over. */
  case class Handoff(location: URL) extends Render

  /** What was submitted names a user another cell holds, and is to be checked there: the browser repeats the submission
    * in `home` (`CellHandoff`, versolauth/versola#1048). The controller that took the submission seals it, since the
    * seal is bound to the cookie the request came with, and then ends this cell's conversation
    * (`ConversationRouter.end`).
    *
    * @param passkeyRequest the assertion request a passkey submission answered
    */
  case class Elsewhere(home: RoutingKey.CellId, passkeyRequest: Option[String]) extends Render

  case class StepPassed(record: ConversationRecord) extends Decision

sealed trait Directive

object Directive:
  case class SetConversationCookie(authId: AuthId) extends Directive
