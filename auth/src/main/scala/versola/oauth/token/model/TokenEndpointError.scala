package versola.oauth.token.model

import versola.oauth.client.model.ClientId
import versola.oauth.model.GrantType
import zio.http.Status


sealed trait TokenEndpointError:
  def status: Status
  def error: String
  def errorDescription: Option[String]
  def errorUri: Option[String]

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

  case object InvalidGrant extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.InvalidGrant
    val errorDescription = Some("Authorization code not found or expired, client_id or redirect_uri not match or PKCE validation failed")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")

  case object UnsupportedGrantType extends TokenEndpointError:
    val status = Status.BadRequest
    val error = ErrorCode.UnsupportedGrantType
    val errorDescription = Some("Unsupported grant type")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")
