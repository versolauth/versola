package versola.edge.session

import versola.edge.model.{AccessTokenId, SessionId}
import zio.Task

import java.time.Instant

trait EdgeSessionRepository:
  /** Upserts the participation row identified by `(publicSessionId, presetId)`,
    * rotating its `accessTokenId` and refresh token. A rotation moves the prior `accessTokenId` to
    * `previousAccessTokenId` and ends any refresh lease: the row is the winner's finished work.
    */
  def create(record: EdgeSessionRecord): Task[Unit]

  def findByAccessTokenId(accessTokenId: AccessTokenId): Task[Option[EdgeSessionRecord]]

  /** The row whose `accessTokenId` was `accessTokenId` before its last rotation, i.e. the one a request
    * with a stale cookie lost the refresh race to. Ignores rows that have expired, and rows rotated
    * at or before `rotatedAfter` (or never rotated): the previous generation's cookie stops being
    * honoured once that moment has passed.
    */
  def findByPreviousAccessTokenId(accessTokenId: AccessTokenId, rotatedAfter: Instant): Task[Option[EdgeSessionRecord]]

  /** Atomically takes the right to refresh the session whose current access token is `accessTokenId`,
    * until `leaseUntil`. None when no live row has that id or another request holds an unexpired
    * lease. Exactly one concurrent caller gets the row, which is what keeps a refresh token from being
    * presented to auth twice: auth treats that as theft and revokes the family.
    *
    * The id is the row's current one, so a row that has since been rotated no longer matches.
    */
  def claimRefresh(accessTokenId: AccessTokenId, now: Instant, leaseUntil: Instant): Task[Option[EdgeSessionRecord]]

  /** Ends the lease taken by [[claimRefresh]] when the refresh did not rotate the row, so waiting
    * requests do not sit out the rest of it. A successful rotation ends it through [[create]].
    */
  def releaseRefresh(accessTokenId: AccessTokenId): Task[Unit]

  def delete(accessTokenId: AccessTokenId): Task[Unit]

  /** Every participation row for `sid`, so callers can enumerate the presets that took part
    * in the SSO session. Ignores expiry: a lapsed row still identifies a preset whose cookie
    * must be cleared.
    *
    * A logout reads these rather than deleting them — what stops the session from being
    * honoured is its revocation, not the absence of a row, and the rows expire on their own.
    */
  def findBySessionId(sid: SessionId): Task[List[EdgeSessionRecord]]
