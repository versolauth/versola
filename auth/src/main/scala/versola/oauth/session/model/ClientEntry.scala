package versola.oauth.session.model

import versola.oauth.client.model.ClientId
import zio.json.JsonCodec

import java.time.Instant

/** A relying party's participation in an SSO session, tracked so logout can notify
  * every client that joined and admins can see when each one entered. */
case class ClientEntry(clientId: ClientId, enteredAt: Instant) derives JsonCodec
