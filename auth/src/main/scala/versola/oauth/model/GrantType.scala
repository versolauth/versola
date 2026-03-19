package versola.oauth.model

import zio.schema.*

/**
 * OAuth 2.1 Grant Types
 */
enum GrantType derives Schema:
  case AuthorizationCode
  case ClientCredentials
  case RefreshToken

object GrantType:
  def from(s: String): Either[String, GrantType] = s match
    case "authorization_code" => Right(GrantType.AuthorizationCode)
    case "client_credentials" => Right(GrantType.ClientCredentials)
    case "refresh_token" => Right(GrantType.RefreshToken)
    case other => Left(s"Unsupported grant type: $other")

