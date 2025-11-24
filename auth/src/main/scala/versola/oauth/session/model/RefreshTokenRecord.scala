package versola.oauth.session.model

import versola.oauth.client.model.ClientId
import versola.user.model.UserId

case class RefreshTokenRecord(userId: UserId, clientId: ClientId)
