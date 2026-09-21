package versola.oauth.authorize.model

import zio.http.Status
import zio.json.*
import zio.schema.*

/** RFC 9126 §2.3: the PAR endpoint reports failures with the token endpoint error format. */
sealed trait PushedAuthorizationError:
  def status: Status
  def error: String
  def errorDescription: Option[String]
  def errorUri: Option[String]

object PushedAuthorizationError:
  case object InvalidClient extends PushedAuthorizationError:
    val status = Status.Unauthorized
    val error: String = ErrorCode.InvalidClient
    val errorDescription = Some("Client not exists, credentials not provided or otherwise invalid")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1")

  case object RequestUriNotAllowed extends PushedAuthorizationError:
    val status = Status.BadRequest
    val error: String = ErrorCode.InvalidRequest
    val errorDescription = Some("The request_uri parameter must not be provided to the pushed authorization request endpoint")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9126#section-2.1")

  case object ClientIdMissing extends PushedAuthorizationError:
    val status = Status.BadRequest
    val error: String = ErrorCode.InvalidRequest
    val errorDescription = Some("Missing required parameter - client_id")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9126#section-2.1")

  case object MethodNotAllowed extends PushedAuthorizationError:
    val status = Status.MethodNotAllowed
    val error: String = ErrorCode.InvalidRequest
    val errorDescription = Some("The pushed authorization request endpoint only accepts POST")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9126#section-2.3")

  case object RequestTooLarge extends PushedAuthorizationError:
    val status = Status.RequestEntityTooLarge
    val error: String = ErrorCode.InvalidRequest
    val errorDescription = Some("The pushed authorization request exceeds the maximum allowed size")
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9126#section-2.3")

  /** RFC 8705 §6.5: the certificate the tenant's proxy forwarded could not be read. Answered
    * as `invalid_client` because that is what it costs the request, but `reason` — which
    * describes the deployment's proxy, not the caller — is logged rather than returned. */
  case class InvalidClientCertificate(reason: String) extends PushedAuthorizationError:
    val status = Status.Unauthorized
    val error: String = ErrorCode.InvalidClient
    val errorDescription = InvalidClient.errorDescription
    val errorUri = Some("https://datatracker.ietf.org/doc/html/rfc8705#section-6.5")

  case class Validation(
      error: String,
      errorDescription: Option[String],
      errorUri: Option[String],
  ) extends PushedAuthorizationError:
    val status = Status.BadRequest

  /** RFC 9126 §2.3: a pushed request is validated as an authorization request, but its errors
    * are returned directly to the client rather than redirected back to it.
    */
  def from(error: Error): PushedAuthorizationError =
    error match
      case Error.BadRequest =>
        Validation(ErrorCode.InvalidRequest, Some(Error.BadRequest.description), None)
      case Error.InvalidRequestObject =>
        Validation(
          Error.InvalidRequestObject.error,
          Some(Error.InvalidRequestObject.description),
          Some("https://datatracker.ietf.org/doc/html/rfc9101#section-6.2"),
        )
      case error: Error.RedirectError =>
        Validation(error.error, Some(error.errorDescription), error.errorUri)

case class PushedAuthorizationErrorResponse(
    error: String,
    @jsonField("error_description") errorDescription: Option[String],
    @jsonField("error_uri") errorUri: Option[String],
) derives Schema, JsonCodec

object PushedAuthorizationErrorResponse:
  def from(error: PushedAuthorizationError): PushedAuthorizationErrorResponse =
    PushedAuthorizationErrorResponse(
      error = error.error,
      errorDescription = error.errorDescription,
      errorUri = error.errorUri,
    )
