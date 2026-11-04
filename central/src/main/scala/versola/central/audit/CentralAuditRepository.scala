package versola.central.audit

import versola.util.audit.{AuditEvent, AuditEventRepository}
import zio.Task

import java.time.Instant
import java.util.UUID

/** Central's audit log: the storage the writer of [[versola.util.audit.AuditEvents]] uses, and what the per-user report reads. */
trait CentralAuditRepository extends AuditEventRepository:

  /** The rows about `userId` (its `user_id` column: what an administrator did to the user or looked at), newest first, in
    * `[from, until)`; with `before`, only those that come after the key in that order (`at`, then `id`, both descending), which
    * is how a page is continued without losing rows of the same instant. */
  def userEvents(userId: UUID, from: Instant, until: Instant, before: Option[CentralAuditRepository.Key], limit: Int): Task[List[AuditEvent]]

object CentralAuditRepository:
  /** A position in the order of the log: where the previous page ended. */
  case class Key(at: Instant, id: UUID)
