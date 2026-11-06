package versola.oauth.revocation

import versola.util.revocation.{Revocation, RevocationRepository}
import zio.Task

/** The durable copy of the revocation list in auth's database: what [[RevocationRepository]] reads,
  * plus how this service writes it.
  *
  * The revocations written because a session or a refresh family ended are not written through
  * here: they are written by the repository method that ends it, in its transaction (see
  * `SessionRepository`), and share the upsert of the Postgres implementation.
  */
trait AuthRevocationRepository extends RevocationRepository:
  /** Records a revocation, widening any key already recorded rather than skipping it: a key can
    * be revoked twice, and the second one covers tokens the first did not. Neither field is ever
    * narrowed, so two writes can arrive in either order.
    *
    * Every replica, including the one writing, learns about it through
    * [[versola.util.revocation.RevocationNotifications]] rather than from the return value.
    */
  def revoke(revocation: Revocation, cause: RevocationCause): Task[Unit]
