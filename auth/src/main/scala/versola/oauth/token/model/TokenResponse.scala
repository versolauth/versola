package versola.oauth.token.model

import zio.json.*
import zio.schema.*

/**
 * OAuth 2.0 Token Response
 * RFC 6749 Section 5.1: https://datatracker.ietf.org/doc/html/rfc6749#section-5.1
 *
 * OpenID Connect Core 1.0 Section 3.1.3.3: https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse
 */
case class TokenResponse(
    @jsonField("access_token") accessToken: String,
    @jsonField("token_type") tokenType: String,
    @jsonField("expires_in") expiresIn: Long,
    @jsonField("refresh_token") refreshToken: Option[String],
    scope: Option[String],
    @jsonField("id_token") idToken: Option[String],
) derives Schema, JsonCodec

