package versola.central.audit

import versola.util.audit.{AuditDraft, AuditEvents, AuditUnavailable}
import zio.*

import scala.util.chaining.*

/** An [[AuditEvents]] that keeps the drafts it was handed, for a spec of a service that records an event of its own. */
final class RecordedAudit(seen: Ref[Vector[AuditDraft]]) extends AuditEvents:
  override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = seen.update(_ :+ draft)

  override def recordBestEffort(draft: AuditDraft): UIO[Unit] = seen.update(_ :+ draft)

  def drafts: UIO[Vector[AuditDraft]] = seen.get

object RecordedAudit:
  def make: UIO[RecordedAudit] = Ref.make(Vector.empty[AuditDraft]).map(RecordedAudit(_))

  /** For a spec whose environment is built outside an effect. */
  def unsafe: RecordedAudit = Unsafe.unsafe(Ref.unsafe.make(Vector.empty[AuditDraft])(using _)).pipe(RecordedAudit(_))
