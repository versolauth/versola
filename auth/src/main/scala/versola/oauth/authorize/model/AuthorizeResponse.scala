package versola.oauth.authorize.model

import versola.oauth.conversation.model.AuthId
import versola.oauth.model.AuthorizationCode
import zio.http.URL

sealed trait AuthorizeResponse

object AuthorizeResponse:
  case class Authorized(
      code: AuthorizationCode,
      idToken: Option[String],
  ) extends AuthorizeResponse

  case class Initialize(
      authId: AuthId,
  ) extends AuthorizeResponse

  /** The address the request named (`login_hint`) belongs to a user of another cell: the conversation this cell started
    * for it is over, and the browser is sent to the home cell with the request sealed (`CellHandoff`,
    * versolauth/versola#1046). */
  case class HandedOff(
      location: URL,
  ) extends AuthorizeResponse
