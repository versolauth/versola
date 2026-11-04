package versola.audit

import versola.util.audit.{ActorType, AuditContext, AuditDraft, AuditEvents, AuditUnavailable}
import zio.*

/** An [[AuditEvents]] for the specs of the call sites: it keeps every draft it is given, in order, so that a case can say which event
  * (kind, outcome, reason, details) the code under test recorded and which it did not. With `unavailable` it refuses, as a full queue
  * does under `overflow = block`. */
final class CapturingAuditEvents private (
    drafts: Ref[Vector[AuditDraft]],
    contextActors: Ref[Vector[Option[(ActorType, Option[String])]]],
    unavailable: Boolean,
) extends AuditEvents:
  override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] =
    if unavailable then ZIO.fail(AuditUnavailable) else keep(draft)

  override def recordBestEffort(draft: AuditDraft): UIO[Unit] = keep(draft)

  /** The draft, and who the request's context said acts at that moment (the actor of a draft that names none). */
  private def keep(draft: AuditDraft): UIO[Unit] =
    AuditContext.current.flatMap(context => contextActors.update(_ :+ context.actor)) *> drafts.update(_ :+ draft)

  /** The actor of the request's context when each draft was recorded, oldest first. */
  val actorsOfContext: UIO[List[Option[(ActorType, Option[String])]]] = contextActors.get.map(_.toList)

  /** What was recorded, oldest first. */
  val recorded: UIO[List[AuditDraft]] = drafts.get.map(_.toList)

  /** Forgets what was recorded, for a spec that shares one instance between its cases. */
  val clear: UIO[Unit] = drafts.set(Vector.empty) *> contextActors.set(Vector.empty)

  /** The drafts of one kind. */
  def of(kind: versola.util.audit.AuditKind): UIO[List[AuditDraft]] = recorded.map(_.filter(_.kind == kind))

object CapturingAuditEvents:
  def make: UIO[CapturingAuditEvents] = create(unavailable = false)

  private def create(unavailable: Boolean): UIO[CapturingAuditEvents] =
    for
      drafts <- Ref.make(Vector.empty[AuditDraft])
      actors <- Ref.make(Vector.empty[Option[(ActorType, Option[String])]])
    yield new CapturingAuditEvents(drafts, actors, unavailable)

  /** For a spec whose service is built once at the top of its object: cases `clear` it first and read `recorded` after. */
  def shared(unavailable: Boolean = false): CapturingAuditEvents =
    Unsafe.unsafe((u: Unsafe) ?=>
      new CapturingAuditEvents(
        Ref.unsafe.make(Vector.empty[AuditDraft])(using u),
        Ref.unsafe.make(Vector.empty[Option[(ActorType, Option[String])]])(using u),
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
