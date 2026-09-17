package versola.loadgen.sut

import versola.loadgen.config.{SutDatabaseConfig, SutStatsDatabaseConfig}
import versola.loadgen.store.{SutStatPhase, SutStatSnapshotRepository, SutStatSnapshotRow}
import zio.{Clock, Scope, Task, UIO, ZIO}

import java.sql.{Connection, DriverManager}
import java.util.Properties

/** The `pg_stat_*` half of the campaign report (runbook 05-report-spec.md §3): a snapshot of every
  * SUT database at the start of the run and another at the end, and the difference between them.
  *
  * Why the coordinator reaches into the SUT's databases at all, rather than reading a Prometheus
  * that already knows: 08-report-data-gaps.md weighs the two, and the exporter the Prometheus
  * option needs -- `postgres_exporter` -- exists neither in the cluster nor in the chart. This
  * needs a connection string and closes §3 and the whole of 07-wal-tuning.md; that needs an
  * exporter deployed, scraped and dashboarded first, and closes the same sections no better.
  *
  * A capture is a *procedure*, not a gauge. Every figure in those two documents is a counter
  * cumulative since the last reset, so a single reading of a running campaign answers nothing;
  * the pair does.
  */
trait SutStatsCapture:

  /** Takes one boundary's snapshot of every configured database.
    *
    * Cannot fail, by design. This runs inside the campaign's start and stop transitions, and a
    * SUT database that is unreachable, has revoked the coordinator's grant, or has moved a column
    * must cost the campaign its database section and nothing else -- refusing to start a
    * ten-hour run over a statistics query would be the wrong trade in the wrong direction.
    */
  def capture(campaign: String, phase: SutStatPhase): UIO[Unit]

  /** The differences for every database that has both of its boundaries recorded. Read from the
    * store rather than from memory, so a coordinator that took over mid-campaign still reports
    * the run its predecessor started.
    */
  def deltas(campaign: String): Task[List[SutStatsDelta]]

final class PostgresSutStatsCapture(
    databases: List[SutStatsDatabaseConfig],
    snapshots: SutStatSnapshotRepository,
) extends SutStatsCapture:

  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    ZIO.foreachDiscard(databases): target =>
      captureOne(campaign, phase, target).catchAllCause: cause =>
        ZIO.logWarningCause(
          s"Could not capture pg_stat_* from the SUT's '${target.name}' database " +
            s"${SutStatsCapture.label(phase)} campaign '$campaign'; the report will have no section for it",
          cause,
        )

  override def deltas(campaign: String): Task[List[SutStatsDelta]] =
    snapshots.loadCampaign(campaign).map(SutStatsDelta.from)

  private def captureOne(campaign: String, phase: SutStatPhase, target: SutStatsDatabaseConfig): Task[Unit] =
    ZIO.scoped:
      for
        connection <- connect(target.database)
        reading <- SutStatsReader.read(connection)
        now <- Clock.instant
        _ <- snapshots.append(
          SutStatSnapshotRow(
            campaign = campaign,
            database = target.name,
            phase = phase,
            capturedAt = now,
            serverVersionNum = reading.serverVersionNum,
            statsResetAt = reading.statsResetAt,
            walStatsResetAt = reading.walStatsResetAt,
            statistics = reading.stats,
          ),
        )
        _ <- ZIO.logInfo(
          s"Captured pg_stat_* from the SUT's '${target.name}' database ${SutStatsCapture.label(phase)} " +
            s"campaign '$campaign' (server_version_num ${reading.serverVersionNum})",
        )
      yield ()

  /** A bare connection per capture, opened and closed around it, as `Seeder`'s own bare-connection
    * helper does. Twice a campaign is not a pool, and one that lived for the run would sit
    * idle in the SUT's connection budget for ten hours -- and in the backend count this very
    * capture reports.
    *
    * The two timeouts are the difference from the seeder's connection, and they are why this
    * builds `Properties` rather than passing user and password positionally: a campaign boundary
    * waits on this call, and pgjdbc's default is to wait on an unresponsive server forever.
    */
  private def connect(config: SutDatabaseConfig): ZIO[Scope, Throwable, Connection] =
    val properties = Properties()
    properties.setProperty("user", config.user)
    properties.setProperty("password", String(config.password.value.toArray))
    properties.setProperty("connectTimeout", SutStatsCapture.connectTimeoutSeconds.toString)
    properties.setProperty("socketTimeout", SutStatsCapture.socketTimeoutSeconds.toString)
    ZIO.acquireRelease(ZIO.attemptBlocking(DriverManager.getConnection(config.url, properties)))(connection =>
      ZIO.attemptBlocking(connection.close()).orDie,
    )

object SutStatsCapture:

  val connectTimeoutSeconds: Int = 10

  /** Deliberately above [[SutStatsReader.queryTimeoutSeconds]]: the statement timeout is the one
    * that should fire on a wedged query, because it names which query wedged. This one is the
    * backstop for a connection that stops answering altogether.
    */
  val socketTimeoutSeconds: Int = 30

  private[sut] def label(phase: SutStatPhase): String = phase match
    case SutStatPhase.Before => "before"
    case SutStatPhase.After => "after"
