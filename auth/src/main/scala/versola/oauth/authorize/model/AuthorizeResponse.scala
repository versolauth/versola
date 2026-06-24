package versola.oauth.authorize.model

import versola.oauth.client.model.PrimaryCredential
import versola.oauth.conversation.model.AuthId
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
