package versola.oauth.session.model

import versola.oauth.model.AccessToken
import versola.user.model.UserId

/** Outcome of revoking a leaked refresh-token family: the user the family belonged to, and
  * the access tokens it issued that are recent enough to still be worth revoking. */
case class RevokedFamily(
    userId: UserId,
    accessTokens: List[AccessToken],
)
