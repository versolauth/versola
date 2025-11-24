package versola.oauth.conversation

import versola.oauth.conversation.model.{AuthId, ConversationRecord}
import zio.{Duration, Task}

trait ConversationRepository:
  def find(authId: AuthId): Task[Option[ConversationRecord]]

  def create(authId: AuthId, record: ConversationRecord, ttl: Duration): Task[Unit]

  def overwrite(authId: AuthId, conversation: ConversationRecord): Task[Unit]

  def delete(authId: AuthId): Task[Unit]
