package versola.oauth.authorize.model

import versola.oauth.conversation.model.{AuthId, PrimaryCredential}
import versola.oauth.model.AuthorizationCode

sealed trait AuthorizeResponse

object AuthorizeResponse:
  case class Authorized(
      code: AuthorizationCode,
  ) extends AuthorizeResponse

  case class Initialize(
      authId: AuthId,
  ) extends AuthorizeResponse
