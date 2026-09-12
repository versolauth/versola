package versola.oauth.session.model

import versola.oauth.model.AccessToken
import versola.user.model.UserId

import java.time.Instant

/** Outcome of revoking a leaked refresh-token family: the user the family belonged to, and
  * the access tokens it issued that are recent enough to still be worth revoking. */
case class RevokedFamily(
    userId: UserId,
    accessTokens: List[AccessToken],
    /** The furthest `access_token_expires_at` among `accessTokens`, i.e. how long the edge
      * revocation entry has to be kept for it to still cover every token in the batch. `None`
      * when `accessTokens` is empty. */
    accessTokensExpireBy: Option[Instant],
)
