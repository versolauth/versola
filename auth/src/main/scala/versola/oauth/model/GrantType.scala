package versola.oauth.model

import zio.schema.*

/**
 * OAuth 2.0 Grant Types
 * Currently only authorization_code is implemented
 */
enum GrantType derives Schema:
  case AuthorizationCode

object GrantType:
  def from(s: String): Either[String, GrantType] = s match
    case "authorization_code" => Right(GrantType.AuthorizationCode)
    case other => Left(s"Unsupported grant type: $other")

