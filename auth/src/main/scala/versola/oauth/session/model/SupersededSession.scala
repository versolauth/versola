package versola.oauth.session.model

import versola.user.model.UserId
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** A session of one cell that a sign-in finished in another cell replaces (versolauth/versola#1048): named by its
  * public id, which carries the routing key of its cell, and by its user, without whom a session id is not acted on.
  */
final case class SupersededSession(userId: UserId, sessionId: PublicSessionId)

object SupersededSession:
  private given JsonCodec[PublicSessionId] =
    JsonCodec(JsonEncoder.string.contramap[PublicSessionId](identity[String]), JsonDecoder.string.map(PublicSessionId(_)))

  given JsonCodec[SupersededSession] = zio.json.DeriveJsonCodec.gen[SupersededSession]
