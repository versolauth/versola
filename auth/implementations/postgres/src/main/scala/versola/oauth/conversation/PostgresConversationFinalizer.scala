package versola.oauth.conversation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.PostgresAuthorizationCodeRepository
import versola.oauth.session.PostgresSessionRepository
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

/** Postgres implementation of [[ConversationFinalizer]].
  *
  * Deliberately does not have its own SQL: it holds one instance each of
  * [[PostgresConversationRepository]], [[PostgresAuthorizationCodeRepository]] and
  * [[PostgresSessionRepository]] and calls their `private[oauth]` "raw" variants — the same code
  * that backs `delete`/`create`/`create` on those classes, just without their own
  * connect/transact wrapper — from inside one `xa.transactMeasured` block. This way there is
  * exactly one place each statement is written; changing one of those methods automatically keeps
  * this transaction in sync instead of relying on a "mirror this" comment.
  */
class PostgresConversationFinalizer(xa: TransactorZIO) extends ConversationFinalizer, BasicCodecs:
  private val conversations = PostgresConversationRepository(xa)
  private val codes = PostgresAuthorizationCodeRepository(xa)
  private val sessions = PostgresSessionRepository(xa)

  override def finish(request: FinishConversationRequest): Task[Boolean] =
    import request.*
    Clock.instant.flatMap: now =>
      xa.transactMeasured("finish-conversation"):
        val claimed = conversations.deleteRaw(authId, version)
        if claimed then
          codes.insertRaw(codeMac, codeRecord, now.plusSeconds(codeTtl.toSeconds))
          val idleExpiresAt = sessionIdleTtl.map(t => now.plusSeconds(t.toSeconds))
          sessions.createRaw(
            sessionIdMac,
            publicSessionId,
            session,
            now,
            now.plusSeconds(sessionTtl.toSeconds),
            idleExpiresAt,
            priorSession,
          )
        claimed

object PostgresConversationFinalizer:
  def live: ZLayer[TransactorZIO, Throwable, ConversationFinalizer] =
    ZLayer.fromFunction(PostgresConversationFinalizer(_))
