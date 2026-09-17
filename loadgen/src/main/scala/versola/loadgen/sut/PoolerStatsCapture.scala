package versola.loadgen.sut

import versola.loadgen.config.{PoolerConfig, SutDatabaseConfig}
import versola.loadgen.store.{PoolerStatSnapshotRepository, PoolerStatSnapshotRow, SutStatPhase}
import zio.{Clock, Scope, Task, UIO, ZIO}

import java.sql.{Connection, DriverManager}
import java.util.Properties

/** The PgBouncer half of the campaign report (runbook 05-report-spec.md §4): an admin console
  * reading at the start of the run and another at the end, and the difference between them.
  *
  * The same bracket as [[SutStatsCapture]], and deliberately the same transport. PgBouncer's
  * admin console speaks the Postgres wire protocol, so the channel 08-report-data-gaps.md chose
  * for §3 -- a direct connection rather than an exporter -- reaches §4's pooler without a second
  * decision, a second deployment or a PgBouncer exporter that exists in the cluster no more than
  * `postgres_exporter` does.
  *
  * The bracket does not reach the half of §4 that is not cumulative: the peak of the queue and
  * the wait quantile. Two boundaries cannot produce either, and [[PoolerPoolStats.maxWaitMicros]]
  * says so where the field is. That half is [[sample]]'s, off the same console on a timer, and
  * [[PoolerQueuePeak]] states what a sampled series does and does not entitle the report to
  * claim. The run's total wait over the run's queries stays the bracket's, and it is the figure
  * §4 requires an answer to.
  */
trait PoolerStatsCapture:

  /** Takes one boundary's reading of every configured pooler. Cannot fail, for
    * [[SutStatsCapture.capture]]'s reason.
    */
  def capture(campaign: String, phase: SutStatPhase): UIO[Unit]

  /** Reads `SHOW POOLS` off every configured pooler once and folds it into the campaign's queue
    * accumulation. Driven on [[PoolerQueueRecorder.sampleInterval]] by the coordinator, which is
    * what decides that only a running campaign is sampled.
    *
    * Cannot fail, for [[capture]]'s reason, and logs a failure below warning level unlike
    * [[capture]]: this runs every few seconds for the length of the run, so a pooler that is
    * unreachable for an hour would put several hundred warnings in the coordinator's log for one
    * fact. [[PoolerQueuePeak.samples]] is where that fact belongs, and it carries it.
    */
  def sample: UIO[Unit]

  /** The differences for every pooler that has both of its boundaries recorded. */
  def deltas(campaign: String): Task[List[PoolerStatsDelta]]

  /** The queue peaks and wait quantiles accumulated since the campaign's opening boundary. */
  def peaks(campaign: String): UIO[List[PoolerQueuePeak]]

final class PgBouncerStatsCapture(
    poolers: List[PoolerConfig],
    snapshots: PoolerStatSnapshotRepository,
    queue: PoolerQueueRecorder,
) extends PoolerStatsCapture:

  /** Arms the accumulation before the boundary rather than after it: the "before" capture is what
    * the coordinator holds a driver's `GET /plan` back until, so anything armed after it would
    * miss the opening seconds of traffic that the capture exists to precede.
    */
  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    ZIO.when(phase == SutStatPhase.Before)(queue.arm(campaign)) *>
      ZIO.foreachDiscard(poolers): target =>
        captureOne(campaign, phase, target).catchAllCause: cause =>
          ZIO.logWarningCause(
            s"Could not read the '${target.name}' PgBouncer admin console " +
              s"${SutStatsCapture.label(phase)} campaign '$campaign'; the report will have no pooler section for it",
            cause,
          )

  override def sample: UIO[Unit] =
    ZIO.foreachDiscard(poolers): target =>
      sampleOne(target).catchAllCause: cause =>
        ZIO.logDebugCause(s"Could not sample the '${target.name}' PgBouncer queue", cause)

  override def deltas(campaign: String): Task[List[PoolerStatsDelta]] =
    snapshots.loadCampaign(campaign).map(PoolerStatsDelta.from)

  override def peaks(campaign: String): UIO[List[PoolerQueuePeak]] = queue.peaks(campaign)

  /** A connection per sample, closed with the scope, rather than one held for the run.
    *
    * The admin console is the one thing in the stack this must not be clever about: a connection
    * parked on it for ten hours survives no PgBouncer restart, no `RELOAD`, and no network blip,
    * and the failure mode is a sampler that reports `samples` climbing while reading a socket
    * that answers nothing. Reconnecting every interval costs one login against the console's own
    * process and makes a restart cost one dropped sample.
    */
  private def sampleOne(target: PoolerConfig): Task[Unit] =
    ZIO.scoped:
      for
        connection <- connect(target.admin)
        pools <- PoolerStatsReader.readPools(connection)
        _ <- queue.record(target.name, pools)
      yield ()

  private def captureOne(campaign: String, phase: SutStatPhase, target: PoolerConfig): Task[Unit] =
    ZIO.scoped:
      for
        connection <- connect(target.admin)
        reading <- PoolerStatsReader.read(connection)
        now <- Clock.instant
        _ <- snapshots.append(
          PoolerStatSnapshotRow(
            campaign = campaign,
            pooler = target.name,
            phase = phase,
            capturedAt = now,
            version = reading.version,
            statistics = reading.stats,
          ),
        )
        _ <- ZIO.logInfo(
          s"Read the '${target.name}' PgBouncer admin console ${SutStatsCapture.label(phase)} " +
            s"campaign '$campaign' (${reading.version})",
        )
      yield ()

  /** [[SutStatsCapture]]'s own bare connection with its two timeouts, plus two properties
    * neither of which is a tuning knob: without both, pgjdbc cannot open this connection at all.
    *
    * `preferQueryMode=simple` -- PgBouncer's console implements only the simple query protocol
    * and rejects pgjdbc's default `Parse` message with "unsupported pkt type", a connection that
    * authenticates and then fails on every statement.
    *
    * `replication=database` -- nothing below touches pgjdbc's replication API; the property is
    * repurposed to skip a step. On every connection, before the first query, pgjdbc sends `SET
    * extra_float_digits = ...`, keyed off the server version just reported in the welcome
    * message. The console reports its own version there -- `1.18.0/bouncer` -- which parses as
    * pre-9.0, and the console fakes exactly six parameters for a client's unprompted `SET`;
    * `extra_float_digits` is not one of them, so every released PgBouncer through 1.25.2 answers
    * "unknown parameter" and drops the connection before a single `SHOW` runs. Setting
    * `replication` makes pgjdbc skip `runInitialQueries` entirely (its early-return guard checks
    * only that the property is non-null, not that the replication API is ever called), which is
    * the one property that removes the `SET` rather than changing what it says. See
    * pgbouncer/pgbouncer#1509 -- fixed on `master`, not yet in any tagged release.
    */
  private def connect(config: SutDatabaseConfig): ZIO[Scope, Throwable, Connection] =
    val properties = Properties()
    properties.setProperty("user", config.user)
    properties.setProperty("password", String(config.password.value.toArray))
    properties.setProperty("connectTimeout", SutStatsCapture.connectTimeoutSeconds.toString)
    properties.setProperty("socketTimeout", SutStatsCapture.socketTimeoutSeconds.toString)
    properties.setProperty("preferQueryMode", "simple")
    properties.setProperty("replication", "database")
    ZIO.acquireRelease(ZIO.attemptBlocking(DriverManager.getConnection(config.url, properties)))(connection =>
      ZIO.attemptBlocking(connection.close()).orDie,
    )
