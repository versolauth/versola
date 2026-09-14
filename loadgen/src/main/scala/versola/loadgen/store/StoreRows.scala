package versola.loadgen.store

import java.time.Instant

/** One `vu_events` row (migration V0003): a sampled step, written on the deferred path.
  *
  * `vu_users` and `vu_sessions` have no row type here -- the repositories read and write
  * `versola.loadgen.model.VirtualUser` / `DeviceSession` directly, whose field order is V0001's
  * and V0002's column order.
  */
case class EventRow(
    at: Instant,
    userId: Long,
    scenario: String,
    step: String,
    outcome: String,
    latencyMs: Int,
)

/** Deferred `vu_users.last_seen_at` write. Its own type rather than a tuple so the write-behind
  * batch is self-describing at the point it is applied.
  */
case class UserTouch(userId: Long, lastSeenAt: Instant)

/** Deferred `vu_sessions.access_expires_at` write.
  *
  * Dev spec §7.5 lists `acr` in the deferred class alongside this column. It is on the critical
  * path instead: both occasions that change a session's `acr` -- a refresh exchange and a
  * completed step-up -- already write a credential at the same moment, so the column costs
  * nothing extra there, while a dropped `acr` would leave a resumed session claiming an
  * assurance level it does not hold. §7.4 then reads it to decide whether the next L2 action
  * needs another step-up.
  */
case class SessionTouch(sessionId: Long, accessExpiresAt: Instant)

/** One `vu_metric_snapshots` row (migration V0004): a driver's HdrHistogram for one measurement
  * over one 60-second interval (§11), as handed to the coordinator's merge.
  *
  * Deliberately primitives rather than track F's `EncodedHistogram`/`MeasurementId`: the store
  * persists the wire form, and F owns what produces it. `histogram` is HdrHistogram's own
  * compressed payload, base64url-encoded -- `wireVersion` is F's envelope version, carried so a
  * coordinator reading a snapshot written by an older driver can refuse it instead of decoding
  * it wrongly.
  */
case class MetricSnapshotRow(
    campaign: String,
    driverId: String,
    capturedAt: Instant,
    wireVersion: Int,
    kind: MeasurementKind,
    /** The scenario a step belongs to; `None` for a flow, which is named on its own (§11). */
    scenario: Option[String],
    /** The step name, or the flow name when [[kind]] is [[MeasurementKind.Flow]]. */
    name: String,
    unit: String,
    sampleCount: Long,
    histogram: String,
)

/** Which of §11's two granularities a [[MetricSnapshotRow]] measures. Mirrors track F's
  * `MeasurementId` without depending on it -- the store's business is the column, not the label.
  */
enum MeasurementKind:
  case Step, Flow
