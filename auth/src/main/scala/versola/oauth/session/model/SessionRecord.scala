package versola.oauth.session.model

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import zio.prelude.These

case class SessionRecord(
    userId: UserId,
    clientId: ClientId,
)
