package versola.loadgen.metrics

import versola.loadgen.protocol.{ProtocolError, RefreshRejection}
import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** An outcome the behaviour model plans for. These are branches of the session state machine,
  * not defects: `retail-basic` users are *supposed* to be forbidden on ~1% of actions
  * (design doc §3), a payment action is *supposed* to be answered with
  * `insufficient_user_authentication` when the token's acr is below L2 (§2.3), and an access
  * token is *supposed* to expire mid-session. A campaign in which these are zero has failed to
  * exercise the paths that matter most.
  *
  * There is deliberately no conversion between this enum and [[FailedOutcome]], in either
  * direction: the two are the taxonomy's split, and the error budget is defined over the other
  * one only.
  */
enum PlannedOutcome(val label: String):
  case Ok extends PlannedOutcome("ok")
  case StepUp extends PlannedOutcome("stepup")
  case Forbidden extends PlannedOutcome("forbidden")
  case Unauthorized extends PlannedOutcome("unauthorized")

object PlannedOutcome:
  private val byLabel: Map[String, PlannedOutcome] = values.map(o => o.label -> o).toMap

  // The label is the wire representation too, not just the Prometheus one: a dashboard, a stored
  // report and a merged report all name the same bucket the same way.
  given JsonCodec[PlannedOutcome] =
    JsonCodec.string.transformOrFail(
      label => byLabel.get(label).toRight(s"unknown planned outcome: $label"),
      _.label,
    )

  given JsonFieldEncoder[PlannedOutcome] = JsonFieldEncoder.string.contramap(_.label)

  given JsonFieldDecoder[PlannedOutcome] =
    JsonFieldDecoder.string.mapOrFail(label => byLabel.get(label).toRight(s"unknown planned outcome: $label"))

/** An outcome that consumes the error budget: the SUT, the network or the emulator itself did
  * something the behaviour model has no branch for.
  *
  * `RefreshRejected` sits here rather than among the planned outcomes even though the protocol
  * layer models it as an ordinary rejection, because §7.4 and §11 require reuse detection to stay
  * at ~0 for the whole campaign -- a non-zero rate means either two fibers touched one session or
  * the SUT lost a rotation, and in both cases the numbers after that point are not trustworthy.
  */
enum FailedOutcome(val label: String):
  case Transport extends FailedOutcome("transport")
  case UnexpectedStatus extends FailedOutcome("unexpected_status")
  case Malformed extends FailedOutcome("malformed")
  case RefreshRejected extends FailedOutcome("refresh_rejected")

object FailedOutcome:
  private val byLabel: Map[String, FailedOutcome] = values.map(o => o.label -> o).toMap

  given JsonCodec[FailedOutcome] =
    JsonCodec.string.transformOrFail(
      label => byLabel.get(label).toRight(s"unknown failed outcome: $label"),
      _.label,
    )

  given JsonFieldEncoder[FailedOutcome] = JsonFieldEncoder.string.contramap(_.label)

  given JsonFieldDecoder[FailedOutcome] =
    JsonFieldDecoder.string.mapOrFail(label => byLabel.get(label).toRight(s"unknown failed outcome: $label"))

/** How one step ended, classified into exactly one side of the taxonomy. */
enum StepOutcome derives JsonCodec:
  case Planned(outcome: PlannedOutcome)
  case Failed(outcome: FailedOutcome)

  def label: String = this match
    case Planned(outcome) => outcome.label
    case Failed(outcome)  => outcome.label

object StepOutcome:
  val ok: StepOutcome = Planned(PlannedOutcome.Ok)

  /** The single place [[ProtocolError]]'s shape is turned into a taxonomy class.
    *
    * No `case _` fallthrough on purpose. A wildcard would quietly file a future ADT case into
    * whichever bucket it happened to be written next to -- and the direction that mistake takes
    * matters: a new expected outcome landing in the error budget makes a healthy campaign look
    * failed, and a new failure landing among the planned outcomes hides a real defect. With an
    * exhaustive match, adding a case to `ProtocolError` produces a compiler warning here and a
    * `MatchError` at the first call, both of which demand an explicit decision.
    */
  def of(error: ProtocolError): StepOutcome = error match
    case _: ProtocolError.Transport         => Failed(FailedOutcome.Transport)
    case _: ProtocolError.UnexpectedStatus  => Failed(FailedOutcome.UnexpectedStatus)
    case _: ProtocolError.MalformedResponse => Failed(FailedOutcome.Malformed)
    case _: ProtocolError.StepUpRequired    => Planned(PlannedOutcome.StepUp)
    case _: ProtocolError.Forbidden         => Planned(PlannedOutcome.Forbidden)
    case _: ProtocolError.Unauthorized      => Planned(PlannedOutcome.Unauthorized)
    case _: ProtocolError.RefreshRejected   => Failed(FailedOutcome.RefreshRejected)

  def of[A](result: Either[ProtocolError, A]): StepOutcome = result match
    case Left(error) => of(error)
    case Right(_)    => ok

/** The campaign's error taxonomy: two disjoint tallies, and the arithmetic the verdict is drawn
  * from.
  *
  * [[budgetConsumed]] is a function of [[failed]] alone, and `Map[FailedOutcome, Long]` has no
  * inhabitant that means "step-up required" -- so the split is enforced by the types rather than
  * by everyone remembering to subtract three buckets before dividing. Both tallies are reported,
  * because a step-up rate that collapses is as interesting as an error rate that rises.
  */
case class ErrorTaxonomy(
    planned: Map[PlannedOutcome, Long],
    failed: Map[FailedOutcome, Long],
) derives JsonCodec:

  def record(outcome: StepOutcome): ErrorTaxonomy =
    recordMany(outcome, 1L)

  def recordMany(outcome: StepOutcome, count: Long): ErrorTaxonomy =
    outcome match
      case StepOutcome.Planned(o) => copy(planned = planned.updatedWith(o)(c => Some(c.getOrElse(0L) + count)))
      case StepOutcome.Failed(o)  => copy(failed = failed.updatedWith(o)(c => Some(c.getOrElse(0L) + count)))

  /** Per-driver tallies add: every step is counted by exactly one driver (a virtual user is owned
    * by exactly one shard, §6.2), so summing across drivers double-counts nothing.
    */
  def merge(other: ErrorTaxonomy): ErrorTaxonomy =
    ErrorTaxonomy(
      planned = ErrorTaxonomy.sum(planned, other.planned),
      failed = ErrorTaxonomy.sum(failed, other.failed),
    )

  def plannedCount(outcome: PlannedOutcome): Long = planned.getOrElse(outcome, 0L)

  def failedCount(outcome: FailedOutcome): Long = failed.getOrElse(outcome, 0L)

  def plannedTotal: Long = planned.values.sum

  def budgetConsumed: Long = failed.values.sum

  def total: Long = plannedTotal + budgetConsumed

  /** Share of all steps that consumed the error budget. Zero for an empty taxonomy rather than
    * `NaN`, so a report assembled before any traffic ran does not read as a failed verdict.
    */
  def budgetRatio: Double =
    if total == 0L then 0.0 else budgetConsumed.toDouble / total.toDouble

object ErrorTaxonomy:
  val empty: ErrorTaxonomy = ErrorTaxonomy(Map.empty, Map.empty)

  def mergeAll(taxonomies: Iterable[ErrorTaxonomy]): ErrorTaxonomy =
    taxonomies.foldLeft(empty)((acc, one) => acc.merge(one))

  private def sum[A](left: Map[A, Long], right: Map[A, Long]): Map[A, Long] =
    right.foldLeft(left) { case (acc, (key, count)) =>
      acc.updatedWith(key)(existing => Some(existing.getOrElse(0L) + count))
    }

/** Fixed label value for `loadgen_refresh_rejected_total{reason}`.
  *
  * `RefreshRejection.Unknown` carries a free-text detail straight off the SUT's response; putting
  * that in a label value would let the SUT dictate this metric's cardinality, so it collapses to
  * `unknown` here and the detail stays in the logs.
  */
object RefreshRejectionLabel:
  def of(rejection: RefreshRejection): String = rejection match
    case RefreshRejection.AlreadyExchanged => "already_exchanged"
    case RefreshRejection.SessionRevoked   => "session_revoked"
    case RefreshRejection.Expired          => "expired"
    case RefreshRejection.Unknown(_)       => "unknown"
