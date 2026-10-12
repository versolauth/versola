package versola.oauth.identifier

import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.{Email, Phone}
import zio.{Duration, Task}

trait IdentifierChangeIntentRepository:

  /** Records the intent, replacing the user's previous one: only the latest request of a user is
    * ever honoured.
    */
  def create(
      userId: UserId,
      clientId: ClientId,
      kind: IdentifierChangeKind,
      credential: Either[Email, Phone],
      ttl: Duration,
  ): Task[Unit]

  /** Removes and returns the intent when it names exactly this user, client, kind and new
    * identifier and has not expired. A non-matching or expired intent is left alone, so a
    * request that does not match cannot burn the user's real one.
    */
  def consume(
      userId: UserId,
      clientId: ClientId,
      kind: IdentifierChangeKind,
      credential: Either[Email, Phone],
  ): Task[Boolean]
