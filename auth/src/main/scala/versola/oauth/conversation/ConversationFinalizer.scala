package versola.oauth.conversation

import versola.oauth.conversation.model.AuthId
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.model.{PriorSession, PublicSessionId, SessionId, SessionRecord}
import versola.util.MAC
import zio.{Duration, Task}

/** Everything `ConversationFinalizer.finish` needs, bundled by name instead of position.
  *
  * Kept as a named record rather than 11 positional parameters: `codeTtl` and `sessionTtl` are
  * both plain `Duration`, and `codeMac`/`sessionIdMac` are both `MAC.Of[_]` — a positional
  * argument list that long makes it easy to transpose two same-typed arguments without the
  * compiler ever noticing.
  */
case class FinishConversationRequest(
    authId: AuthId,
    version: Long,
    codeMac: MAC.Of[AuthorizationCode],
    codeRecord: AuthorizationCodeRecord,
    codeTtl: Duration,
    sessionIdMac: MAC.Of[SessionId],
    publicSessionId: PublicSessionId,
    session: SessionRecord,
    sessionTtl: Duration,
    sessionIdleTtl: Option[Duration],
    priorSession: Option[PriorSession],
)

/** Atomically completes a conversation: deletes the conversation row and, only if that delete
  * actually claimed it (optimistic version match), creates the authorization code and the SSO
  * session in the same database transaction.
  *
  * This exists so `ConversationService.finish` cannot end up in the state where the conversation
  * is gone but the authorization code / session were never created (see issue #102) — the delete
  * and the two creates either all commit together or all roll back together.
  */
trait ConversationFinalizer:
  /** @return true if the conversation was claimed (deleted) and the authorization code/session
    *         were created; false if the conversation delete didn't match (already finished,
    *         deleted, or concurrently modified) — in which case nothing is written.
    */
  def finish(request: FinishConversationRequest): Task[Boolean]
