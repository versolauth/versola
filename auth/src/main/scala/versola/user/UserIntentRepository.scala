package versola.user

import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import zio.{Duration, Task}

/** What a user has asked for on the account page and not yet had carried out. A request that acts
  * on it (`/authorize` for a change of phone or email) must find exactly that intent first, so it
  * cannot be started by anything but the page.
  */
trait UserIntentRepository:

  /** Records the intent, replacing the user's previous one: only the latest request of a user is
    * ever honoured.
    */
  def create(userId: UserId, clientId: ClientId, intent: UserIntent, ttl: Duration): Task[Unit]

  /** Removes the intent and returns true when it is exactly this user's, for this client, and has
    * not expired. A different or expired intent is left alone, so a request that does not match
    * cannot burn the user's real one.
    */
  def consume(userId: UserId, clientId: ClientId, intent: UserIntent): Task[Boolean]
