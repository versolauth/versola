package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.metrics.{Metric, MetricLabel}

/** What the database's write-ahead log is doing, read from the database itself.
  *
  * Nothing here needs an exporter: the views are readable by an ordinary role, and each service reads
  * its own database. What is published is chosen for the decisions an operator makes about WAL:
  *
  *   - is WAL being written faster than `max_wal_size` / `checkpoint_timeout` allows, so that checkpoints
  *     come from volume instead of the timer (`db_wal_bytes_total` against the two settings),
  *   - are they already coming from volume (`db_checkpoints_total` by type),
  *   - how much of the WAL is full-page images, which `wal_compression` can shrink (`db_wal_fpi_total`),
  *   - is something holding WAL back from being recycled, the usual way `pg_wal` fills a disk
  *     (`db_replication_slot_*`).
  *
  * The counters are cumulative inside Postgres. They are re-published as counters here, so `rate()` works
  * and a reset of the statistics (or a restart of the database) shows as a restart, not a negative rate.
  * Every replica of a service reads the same database and reports the same numbers; read them with
  * `max by (...)`, not `sum`.
  */
object PostgresWalMetrics:

  val DefaultInterval: Duration = 1.minute

  // -- units ---------------------------------------------------------------------------------

  /** A `pg_settings` value in bytes, for the settings measured in a size unit. */
  private[postgres] def bytes(setting: Double, unit: String): Option[Double] =
    unit match
      case "B" => Some(setting)
      case "kB" => Some(setting * 1024)
      case "8kB" => Some(setting * 8192)
      case "MB" => Some(setting * 1024 * 1024)
      case "GB" => Some(setting * 1024 * 1024 * 1024)
      case "TB" => Some(setting * 1024 * 1024 * 1024 * 1024)
      case _ => None

  /** A `pg_settings` value in seconds, for the settings measured in a time unit. */
  private[postgres] def seconds(setting: Double, unit: String): Option[Double] =
    unit match
      case "us" => Some(setting / 1e6)
      case "ms" => Some(setting / 1e3)
      case "s" => Some(setting)
      case "min" => Some(setting * 60)
      case "h" => Some(setting * 3600)
      case "d" => Some(setting * 86400)
      case _ => None

  // -- cumulative counters -------------------------------------------------------------------

  /** Republishes a number that only grows inside Postgres as a counter that grows here.
    *
    * The first reading is the baseline, not an increment: what was written before this process started is
    * not this process's to report. A reading below the previous one means the statistics were reset, so
    * everything in it is new.
    */
  private[postgres] final class Cumulative private (counter: Metric.Counter[Double], last: Ref[Option[Double]]):
    def observe(value: Double): UIO[Unit] =
      last.getAndSet(Some(value)).flatMap:
        case Some(previous) if value >= previous => counter.incrementBy(value - previous)
        case Some(_) => counter.incrementBy(value)
        case None => ZIO.unit

  private[postgres] object Cumulative:
    def make(counter: Metric.Counter[Double]): UIO[Cumulative] = Ref.make(Option.empty[Double]).map(Cumulative(counter, _))

  // -- what the database says ----------------------------------------------------------------

  private final case class Wal(bytes: Double, records: Long, fullPageImages: Long)
  private final case class Checkpoints(timed: Long, requested: Long)
  private final case class Settings(maxWalSizeBytes: Option[Double], checkpointTimeoutSeconds: Option[Double])
  private final case class Slot(name: String, active: Boolean, retainedBytes: Double)

  private val maxWalSize = Metric.gauge("db_wal_max_size_bytes")
  private val checkpointTimeout = Metric.gauge("db_checkpoint_timeout_seconds")
  private val retainedBySlots = Metric.gauge("db_wal_retained_by_slots_bytes")
  private val slotRetained = Metric.gauge("db_replication_slot_retained_bytes")
  private val slotActive = Metric.gauge("db_replication_slot_active")

  private def tagged(name: String, key: String, value: String) = Metric.counterDouble(name).tagged(MetricLabel(key, value))

  /** Reads the database once and publishes. Each group of readings fails on its own (a view this role cannot
    * read, a setting the server does not have) without taking the others with it.
    */
  final class Sampler private[postgres] (
      xa: TransactorZIO,
      walBytes: Cumulative,
      walRecords: Cumulative,
      walFpi: Cumulative,
      timed: Cumulative,
      requested: Cumulative,
      seenSlots: Ref[Set[String]],
      warned: Ref[Set[String]],
  ) extends BasicCodecs:

    private def tolerate(group: String)(read: Task[Unit]): UIO[Unit] =
      read.catchAllCause: cause =>
        warned.getAndUpdate(_ + group).flatMap: before =>
          // once per group, then quietly: a role that cannot read a view would otherwise log every interval
          if before.contains(group) then ZIO.logDebugCause(s"Could not read $group", cause)
          else ZIO.logWarningCause(s"Could not read $group for the WAL metrics; it will not be reported", cause)

    def sample: UIO[Unit] =
      wal *> checkpoints *> settings *> slots

    // pg_stat_wal: PostgreSQL 14 and later
    private def wal: UIO[Unit] =
      tolerate("pg_stat_wal"):
        xa.connectMeasured("wal-stat-wal") {
          sql"SELECT wal_bytes::float8, wal_records, wal_fpi FROM pg_stat_wal".query[(Double, Long, Long)].run().headOption
        }.flatMap(
          ZIO.foreachDiscard(_)((b, r, f) => walBytes.observe(b) *> walRecords.observe(r.toDouble) *> walFpi.observe(f.toDouble)),
        )

    // pg_stat_checkpointer exists from PostgreSQL 17; before that the same counts lived in pg_stat_bgwriter
    private def checkpoints: UIO[Unit] =
      tolerate("pg_stat_checkpointer"):
        xa.connectMeasured("wal-stat-checkpointer") {
          sql"SELECT num_timed, num_requested FROM pg_stat_checkpointer".query[(Long, Long)].run().headOption
        }.orElse(
          xa.connectMeasured("wal-stat-bgwriter") {
            sql"SELECT checkpoints_timed, checkpoints_req FROM pg_stat_bgwriter".query[(Long, Long)].run().headOption
          },
        ).flatMap(ZIO.foreachDiscard(_)((t, r) => timed.observe(t.toDouble) *> requested.observe(r.toDouble)))

    private def settings: UIO[Unit] =
      tolerate("pg_settings"):
        xa.connectMeasured("wal-settings") {
          sql"""
            SELECT name, setting, COALESCE(unit, '') FROM pg_settings
            WHERE name IN ('max_wal_size', 'checkpoint_timeout')
          """.query[(String, String, String)].run().toList
        }.flatMap: rows =>
          val read = rows.flatMap((name, setting, unit) => setting.toDoubleOption.map(name -> (_, unit))).toMap
          ZIO.foreachDiscard(read.get("max_wal_size"))((value, unit) => ZIO.foreachDiscard(bytes(value, unit))(maxWalSize.set)) *>
            ZIO.foreachDiscard(read.get("checkpoint_timeout"))((value, unit) => ZIO.foreachDiscard(seconds(value, unit))(checkpointTimeout.set))

    /** WAL a slot is holding back: the distance from the current position to the oldest WAL the slot still
      * needs. An inactive slot that nothing is consuming keeps that growing until the disk is full.
      */
    private def slots: UIO[Unit] =
      tolerate("pg_replication_slots"):
        xa.connectMeasured("wal-replication-slots") {
          sql"""
            SELECT slot_name::text, active,
                   COALESCE(pg_wal_lsn_diff(
                     CASE WHEN pg_is_in_recovery() THEN pg_last_wal_replay_lsn() ELSE pg_current_wal_lsn() END,
                     restart_lsn), 0)::float8
            FROM pg_replication_slots
          """.query[(String, Boolean, Double)].run().toList
        }.flatMap: rows =>
          val found = rows.map(Slot.apply.tupled)
          for
            before <- seenSlots.getAndSet(found.map(_.name).toSet)
            // a gauge cannot be removed, so a slot that was dropped is set to NaN: neither the graph nor the idle-slot count (== 0) picks it up
            _ <- ZIO.foreachDiscard(before -- found.map(_.name))(name =>
              slotRetained.tagged(MetricLabel("slot", name)).set(Double.NaN) *> slotActive.tagged(MetricLabel("slot", name)).set(Double.NaN),
            )
            _ <- ZIO.foreachDiscard(found)(slot =>
              slotRetained.tagged(MetricLabel("slot", slot.name)).set(slot.retainedBytes) *>
                slotActive.tagged(MetricLabel("slot", slot.name)).set(if slot.active then 1 else 0),
            )
            _ <- retainedBySlots.set(found.map(_.retainedBytes).maxOption.getOrElse(0.0))
          yield ()

  object Sampler:
    def make(xa: TransactorZIO): UIO[Sampler] =
      for
        b <- Cumulative.make(Metric.counterDouble("db_wal_bytes_total"))
        r <- Cumulative.make(Metric.counterDouble("db_wal_records_total"))
        f <- Cumulative.make(Metric.counterDouble("db_wal_fpi_total"))
        t <- Cumulative.make(tagged("db_checkpoints_total", "type", "timed"))
        q <- Cumulative.make(tagged("db_checkpoints_total", "type", "requested"))
        seen <- Ref.make(Set.empty[String])
        warned <- Ref.make(Set.empty[String])
      yield Sampler(xa, b, r, f, t, q, seen, warned)

  /** What the layer provides, so that something downstream names it and the layer is not pruned. */
  final class Running private[postgres] ()

  /** Starts sampling when the layer is built and stops when its scope closes. */
  def live(interval: Duration = DefaultInterval): ZLayer[TransactorZIO & Scope, Nothing, Running] =
    ZLayer.fromZIO:
      for
        xa <- ZIO.service[TransactorZIO]
        sampler <- Sampler.make(xa)
        _ <- sampler.sample.repeat(Schedule.spaced(interval)).forkScoped
      yield Running()
