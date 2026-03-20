package versola.oauth.revoke.model

import zio.http.Status
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/**
 * Errors that can occur during token revocation.
 * Per RFC 7009, most errors should return HTTP 200 with success.
 */
enum RevocationError:
  case InvalidClient, UnsupportedTokenType

  def status: Status = this match
    case InvalidClient => Status.Unauthorized
    case UnsupportedTokenType => Status.BadRequest


case class RevocationErrorResponse(
    error: String,
    errorDescription: Option[String] = None,
)

object RevocationErrorResponse:
  given JsonCodec[RevocationErrorResponse] = JsonCodec.derived
  
  def fromError(error: RevocationError): RevocationErrorResponse =
    error match
      case RevocationError.InvalidClient =>
        RevocationErrorResponse(
          error = "invalid_client",
          errorDescription = Some("Client authentication failed"),
        )
      case RevocationError.UnsupportedTokenType =>
        RevocationErrorResponse(
          error = "unsupported_token_type",
          errorDescription = Some("The authorization server does not support the revocation of the presented token type"),
        )

