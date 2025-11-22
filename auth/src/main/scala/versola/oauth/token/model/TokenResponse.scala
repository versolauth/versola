package versola.oauth.token.model

import zio.json.*
import zio.schema.*

/**
 * OAuth 2.0 Token Response
 * RFC 6749 Section 5.1: https://datatracker.ietf.org/doc/html/rfc6749#section-5.1
 */
case class TokenResponse(
    @jsonField("access_token") accessToken: String,
    @jsonField("token_type") tokenType: String,
    @jsonField("expires_in") expiresIn: Long,
    @jsonField("refresh_token") refreshToken: Option[String] = None,
    scope: Option[String] = None,
) derives Schema, JsonCodec

object TokenResponse:
  val TokenType = "Bearer"

