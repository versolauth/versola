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

  /** Deletes every participation row for `sid` and returns the deleted records, so callers
    * can enumerate the presets that participated without a separate lookup. Ignores expiry:
    * a lapsed row still identifies a preset whose cookie must be cleared.
    */
  def deleteBySessionId(sid: SessionId): Task[List[EdgeSessionRecord]]
