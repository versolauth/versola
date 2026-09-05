package versola.oauth.token.model

import versola.oauth.client.model.ResourceUri
import versola.oauth.model.GrantType
import zio.http.Status


sealed trait TokenEndpointError:
  def status: Status
  def error: String
  def errorDescription: Option[String]
  def errorUri: Option[String]

  /** Description recorded in the request's error context, which never leaves the server.
    * Defaults to the client-facing [[errorDescription]]; errors whose response body is
    * deliberately uniform across causes narrow this to the specific check that failed. */
  def logDescription: Option[String] = errorDescription

object TokenEndpointError:
  case object InvalidRequest extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidRequest
    val errorDescription = Some("Request is missing a required parameter, includes an unsupported parameter value, or is otherwise malformed")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")

  case object InvalidClient extends TokenEndpointError:
    val status = Status.Unauthorized
    val error = ErrorCode.InvalidClient
    val errorDescription = Some("Client not exists, credentials not provided or otherwise invalid")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1")

  /** `reason` names the check that failed. It is logged, never returned: the response body
    * stays identical across every cause, so a caller cannot tell an unknown grant from a
    * replayed or misdirected one and use the endpoint as an oracle. */
  case class InvalidGrant(reason: String) extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidGrant
    val errorDescription = Some("Authorization code not found or expired, client_id or redirect_uri not match or PKCE validation failed")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")
    override def logDescription = Some(reason)

  object InvalidGrant:
    val CodeNotFound = InvalidGrant("authorization code not found or expired")
    val CodeClientMismatch = InvalidGrant("authorization code was issued to a different client")
    val RedirectUriMismatch = InvalidGrant("redirect_uri does not match the one the code was issued for")
    val PkceMismatch = InvalidGrant("PKCE code_verifier does not match the code_challenge")
    val CodeReplayed = InvalidGrant("authorization code already used; the token it issued was revoked")
    val RefreshTokenNotFound = InvalidGrant("refresh token not found or expired")
    val RefreshTokenClientMismatch = InvalidGrant("refresh token was issued to a different client")
    val RefreshTokenReplayed = InvalidGrant("refresh token already exchanged for a successor; the chain was revoked")
    val RefreshChainAlreadyExchanged = InvalidGrant("refresh token chain was already exchanged")

  case object UnsupportedGrantType extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.UnsupportedGrantType
    val errorDescription = Some("Unsupported grant type")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")

  case object InvalidScope extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidScope
    val errorDescription = Some("The requested scope is invalid, unknown, or malformed, or exceeds the scope granted by the resource owner")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")

  case class InvalidTarget(resource: ResourceUri) extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidTarget
    val errorDescription = Some(s"The requested resource target is invalid or unknown: $resource")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc8707#section-2.2")

  case class InvalidAuthorizationDetails(reason: String) extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidAuthorizationDetails
    val errorDescription = Some(s"The requested authorization details are invalid or exceed the grant: $reason")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9396#section-6")
