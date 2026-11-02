package versola.oauth.conversation

import versola.oauth.conversation.model.{AuthId, ConversationRecord}
import zio.{Duration, Task}

trait ConversationRepository:
  def find(authId: AuthId): Task[Option[ConversationRecord]]

  def create(authId: AuthId, record: ConversationRecord, ttl: Duration): Task[Unit]

  def overwrite(authId: AuthId, record: ConversationRecord): Task[Boolean]

  /** Counts a wrong solution of the conversation's proof-of-work challenge and returns how many it has made, this one
    * included. One statement, apart from the optimistic overwrite of the record, so that wrong solutions sent at once are
    * all counted. */
  def recordChallengeFailure(authId: AuthId): Task[Int]

  /** Spends a solved challenge: true for exactly one caller per value of `solved` (the number of challenges the conversation
    * had solved when the token was issued), which moves it on, so that the same token cannot pass twice, and forgets the
    * wrong solutions made so far. */
  def consumeChallenge(authId: AuthId, solved: Int): Task[Boolean]

  def delete(authId: AuthId, version: Long): Task[Boolean]
