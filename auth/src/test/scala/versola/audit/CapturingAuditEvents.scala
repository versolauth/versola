package versola.audit

import versola.util.audit.{AuditDraft, AuditEvent, AuditEvents, AuditUnavailable}
import zio.*

import java.util.UUID

/** An [[AuditEvents]] for the specs of the call sites: it keeps every draft it is given, in order, so that a case can say which event
  * (kind, outcome, reason, details) the code under test recorded and which it did not. With `unavailable` it refuses, as a full queue
  * does under `overflow = block`.
  *
  * A queued or `recordNow` event is kept when it is recorded. One built for a repository to insert in its transaction is kept when the
  * service says it `committed`, which is after the repository returned: a draft whose change failed was never recorded. The
  * repositories are stubs here, so what this proves is that the service built the event and handed it down; that the repository
  * inserts it with the change is the repository specs'. `handedDown` is the events a repository stub received. */
final class CapturingAuditEvents private (
    drafts: Ref[Vector[AuditDraft]],
    built: Ref[Map[UUID, AuditDraft]],
    unavailable: Boolean,
) extends AuditEvents:
  override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] =
    if unavailable then ZIO.fail(AuditUnavailable) else drafts.update(_ :+ draft)

  override def recordBestEffort(draft: AuditDraft): UIO[Unit] = drafts.update(_ :+ draft)

  override def recordNow(draft: AuditDraft): IO[AuditUnavailable.type, Unit] =
    if unavailable then ZIO.fail(AuditUnavailable) else drafts.update(_ :+ draft)

  override def build(draft: AuditDraft): UIO[AuditEvent] =
    AuditEvents.noop.build(draft).tap(event => built.update(_ + (event.id -> draft)))

  override def committed(event: AuditEvent): UIO[Unit] =
    built.get.flatMap(_.get(event.id).fold(ZIO.unit)(draft => drafts.update(_ :+ draft)))

  /** The drafts that were built and not (yet) committed: what a repository was handed and may or may not have inserted. */
  val builtOnly: UIO[List[AuditDraft]] = for
    all <- built.get
    kept <- drafts.get
  yield all.values.filterNot(kept.contains).toList

  /** What was recorded, oldest first. */
  val recorded: UIO[List[AuditDraft]] = drafts.get.map(_.toList)

  /** Forgets what was recorded, for a spec that shares one instance between its cases. */
  val clear: UIO[Unit] = drafts.set(Vector.empty) *> built.set(Map.empty)

  /** The drafts of one kind. */
  def of(kind: versola.util.audit.AuditKind): UIO[List[AuditDraft]] = recorded.map(_.filter(_.kind == kind))

object CapturingAuditEvents:
  def make: UIO[CapturingAuditEvents] = create(unavailable = false)

  private def create(unavailable: Boolean): UIO[CapturingAuditEvents] =
    for
      drafts <- Ref.make(Vector.empty[AuditDraft])
      built <- Ref.make(Map.empty[UUID, AuditDraft])
    yield new CapturingAuditEvents(drafts, built, unavailable)

  /** For a spec whose service is built once at the top of its object: cases `clear` it first and read `recorded` after. */
  def shared(unavailable: Boolean = false): CapturingAuditEvents =
    Unsafe.unsafe((u: Unsafe) ?=>
      new CapturingAuditEvents(
        Ref.unsafe.make(Vector.empty[AuditDraft])(using u),
        Ref.unsafe.make(Map.empty[UUID, AuditDraft])(using u),
        unavailable,
      ),
    )

  /** One that refuses every `record`. */
  def refusing: UIO[CapturingAuditEvents] = create(unavailable = true)

  /** A layer of it, for a spec that provides its collaborators as layers. */
  val layer: ULayer[CapturingAuditEvents & AuditEvents] =
    ZLayer.fromZIO(make).flatMap(env => ZLayer.succeedEnvironment(ZEnvironment[CapturingAuditEvents, AuditEvents](env.get, env.get)))

  /** The settings the generated config has. */
  val settings: AuditEvents.Settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  /** Every kind a deployment may leave out, enabled, as the generated config has them. */
  val tiers: AuthAudit.Tiers = AuthAudit.Tiers.all
