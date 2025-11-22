package versola.oauth.token.model

import versola.oauth.model.{ClientId, GrantType}
import zio.http.Status


sealed trait TokenEndpointError:
  def status: Status
  def error: String
  def errorDescription: Option[String]
  def errorUri: Option[String]

object TokenEndpointError:
  sealed trait BadRequest extends TokenEndpointError:
    def status = Status.BadRequest

  sealed trait Unauthorized extends TokenEndpointError:
    def status = Status.Unauthorized

  case object CredentialsNotProvided extends Unauthorized:
    val error = ErrorCode.InvalidClient
    val errorDescription = Some("Credentials are not provided via HTTP Basic authentication scheme")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1")

  case object InvalidCredentials extends Unauthorized:
    val error = ErrorCode.InvalidClient
    val errorDescription = Some("Client not exists or credentials are invalid")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-5.2")





