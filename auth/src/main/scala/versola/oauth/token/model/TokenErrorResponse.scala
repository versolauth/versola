package versola.oauth.token.model

import zio.json.*
import zio.schema.*

/**
 * OAuth 2.0 Token Error Response
 * RFC 6749 Section 5.2: https://datatracker.ietf.org/doc/html/rfc6749#section-5.2
 */
case class TokenErrorResponse(
    error: String,
    @jsonField("error_description") errorDescription: Option[String] = None,
    @jsonField("error_uri") errorUri: Option[String] = None,
) derives Schema, JsonCodec

object TokenErrorResponse:
  def from(tokenError: TokenEndpointError): TokenErrorResponse =
    TokenErrorResponse(
      error = tokenError.error,
      errorDescription = tokenError.errorDescription,
      errorUri = tokenError.errorUri,
    )

