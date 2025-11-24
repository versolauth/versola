package versola.oauth.session.model

import versola.auth.model.RefreshToken
import versola.oauth.client.model.ClientId
import versola.user.model.UserId

case class SessionRecord(
    userId: UserId
)
