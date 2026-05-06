package versola.edge.model

import zio.json.{JsonCodec, jsonField}

case class TokenResponse(
    @jsonField("access_token") accessToken: AccessToken,
    @jsonField("token_type") tokenType: String,
    @jsonField("expires_in") expiresIn: Long,
    @jsonField("refresh_token") refreshToken: Option[RefreshToken],
    @jsonField("refresh_token_expires_in") refreshTokenExpiresIn: Option[Long],
    scope: Option[String],
    @jsonField("id_token") idToken: Option[String],
) derives JsonCodec