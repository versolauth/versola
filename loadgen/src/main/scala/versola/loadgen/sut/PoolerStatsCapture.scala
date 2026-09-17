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
  * What it does not reach is the half of §4 that is not cumulative: the peak of the queue and the
  * wait quantile. Two boundaries cannot produce either, and [[PoolerPoolStats.maxWaitMicros]]
  * says so where the field is. The run's total wait over the run's queries is what a bracket can
  * answer, and it is the figure §4 requires an answer to.
  */
trait PoolerStatsCapture:

  /** Takes one boundary's reading of every configured pooler. Cannot fail, for
    * [[SutStatsCapture.capture]]'s reason.
    */
  def capture(campaign: String, phase: SutStatPhase): UIO[Unit]

  /** The differences for every pooler that has both of its boundaries recorded. */
  def deltas(campaign: String): Task[List[PoolerStatsDelta]]

final class PgBouncerStatsCapture(
    poolers: List[PoolerConfig],
    snapshots: PoolerStatSnapshotRepository,
) extends PoolerStatsCapture:

  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    ZIO.foreachDiscard(poolers): target =>
      captureOne(campaign, phase, target).catchAllCause: cause =>
        ZIO.logWarningCause(
          s"Could not read the '${target.name}' PgBouncer admin console " +
            s"${SutStatsCapture.label(phase)} campaign '$campaign'; the report will have no pooler section for it",
          cause,
        )

  override def deltas(campaign: String): Task[List[PoolerStatsDelta]] =
    snapshots.loadCampaign(campaign).map(PoolerStatsDelta.from)

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
