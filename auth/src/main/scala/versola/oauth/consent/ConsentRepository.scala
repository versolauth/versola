package versola.oauth.consent

import versola.oauth.client.model.ClientId
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import zio.Task

trait ConsentRepository:
  def find(userId: UserId, clientId: ClientId): Task[Option[ConsentRecord]]

  /** Records the grant, replacing any previous grant for the same (user, client) pair. */
  def upsert(record: ConsentRecord): Task[Unit]

  def delete(userId: UserId, clientId: ClientId): Task[Unit]
