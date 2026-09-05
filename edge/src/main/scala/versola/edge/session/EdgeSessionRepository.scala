package versola.edge.session

import versola.edge.model.{AccessTokenId, SessionId}
import zio.Task

trait EdgeSessionRepository:
  /** Upserts the participation row identified by `(publicSessionId, presetId)`,
    * rotating its `accessTokenId` and refresh token.
    */
  def create(record: EdgeSessionRecord): Task[Unit]

  def findByAccessTokenId(accessTokenId: AccessTokenId): Task[Option[EdgeSessionRecord]]

  def delete(accessTokenId: AccessTokenId): Task[Unit]

  /** Every participation row for `sid`, so callers can enumerate the presets that took part
    * in the SSO session. Ignores expiry: a lapsed row still identifies a preset whose cookie
    * must be cleared.
    *
    * A logout reads these rather than deleting them — what stops the session from being
    * honoured is its revocation, not the absence of a row, and the rows expire on their own.
    */
  def findBySessionId(sid: SessionId): Task[List[EdgeSessionRecord]]
