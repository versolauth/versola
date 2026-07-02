package versola.oauth.authorize.model

import versola.oauth.client.model.PrimaryCredential
import versola.oauth.conversation.ConversationResult
import versola.oauth.conversation.model.{AuthId, ConversationRecord}
import versola.oauth.model.AuthorizationCode

sealed trait AuthorizeResponse

object AuthorizeResponse:
  case class Authorized(
      code: AuthorizationCode,
      idToken: Option[String],
  ) extends AuthorizeResponse

  case class Initialize(
      authId: AuthId,
  ) extends AuthorizeResponse

  case class InitializeWithHint(
      authId: AuthId,
      render: ConversationResult.Render,
      conversation: ConversationRecord,
  ) extends AuthorizeResponse
