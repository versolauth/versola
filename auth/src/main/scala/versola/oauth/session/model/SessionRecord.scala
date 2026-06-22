package versola.oauth.session.model

import versola.oauth.client.model.ClientId
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.user.model.UserId
import zio.prelude.These

import java.time.Instant

case class SessionRecord(
    userId: UserId,
    clientId: ClientId,
    userAgent: UserAgentInfo,
    createdAt: Instant,
)
