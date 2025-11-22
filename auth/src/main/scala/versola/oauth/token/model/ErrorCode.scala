package versola.oauth.token.model

/**
 * OAuth 2.0 Token Endpoint Error Codes
 * RFC 6749 Section 5.2: https://datatracker.ietf.org/doc/html/rfc6749#section-5.2
 */
private[token]
type ErrorCode = ErrorCode.Type

private[token]
object ErrorCode:
  opaque type Type <: String = String

  /** The request is missing a required parameter, includes an unsupported parameter value */
  val InvalidRequest: ErrorCode = "invalid_request"
  
  /** Client authentication failed */
  val InvalidClient: ErrorCode = "invalid_client"
  
  /** The provided authorization grant is invalid, expired, revoked, or does not match the redirect URI */
  val InvalidGrant: ErrorCode = "invalid_grant"
  
  /** The authenticated client is not authorized to use this authorization grant type */
  val UnauthorizedClient: ErrorCode = "unauthorized_client"
  
  /** The authorization grant type is not supported by the authorization server */
  val UnsupportedGrantType: ErrorCode = "unsupported_grant_type"
  
  /** The requested scope is invalid, unknown, or malformed */
  val InvalidScope: ErrorCode = "invalid_scope"

