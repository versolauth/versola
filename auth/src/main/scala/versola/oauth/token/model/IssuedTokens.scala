package versola.oauth.token.model

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.ScopeToken
import versola.user.model.UserId
import zio.Duration

case class IssuedTokens(
    accessToken: AccessToken,
    accessTokenTtl: Duration,
    accessTokenJwtProperties: Option[IssuedTokens.JwtProperties],
    refreshToken: Option[RefreshToken],
    scope: Set[ScopeToken],
)

object IssuedTokens:
  case class JwtProperties(
      userId: UserId,
  )