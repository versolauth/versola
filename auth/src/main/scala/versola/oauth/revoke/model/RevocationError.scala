package versola.oauth.revoke.model

import zio.http.Status
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/**
 * Errors that can occur during token revocation.
 * Per RFC 7009, most errors should return HTTP 200 with success.
 */
enum RevocationError:
  case InvalidClient, InvalidRequest, UnsupportedTokenType

  /** RFC 8705 §6.5: the certificate the tenant's proxy forwarded could not be read. Answered
    * as `invalid_client` because that is what it costs the request, but `reason` — which
    * describes the deployment's proxy, not the caller — is logged rather than returned. */
  case InvalidClientCertificate(reason: String)

  def status: Status = this match
    case InvalidClient => Status.Unauthorized
    case InvalidClientCertificate(_) => Status.Unauthorized
    case InvalidRequest => Status.BadRequest
    case UnsupportedTokenType => Status.BadRequest


case class RevocationErrorResponse(
    error: String,
    errorDescription: Option[String],
)

object RevocationErrorResponse:
  given JsonCodec[RevocationErrorResponse] = JsonCodec.derived
  
  def fromError(error: RevocationError): RevocationErrorResponse =
    error match
      case RevocationError.InvalidClient | RevocationError.InvalidClientCertificate(_) =>
        RevocationErrorResponse(
          error = "invalid_client",
          errorDescription = Some("Client authentication failed"),
        )
      case RevocationError.InvalidRequest =>
        RevocationErrorResponse(
          error = "invalid_request",
          errorDescription = Some("The request is missing the required 'token' parameter"),
        )
      case RevocationError.UnsupportedTokenType =>
        RevocationErrorResponse(
          error = "unsupported_token_type",
          errorDescription = Some("The authorization server does not support the revocation of the presented token type"),
        )

