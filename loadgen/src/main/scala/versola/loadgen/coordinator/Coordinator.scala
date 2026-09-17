package versola.loadgen.coordinator

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.config.{LoadgenConfig, SutStatsConfig}
import versola.loadgen.store.{LoadgenMigrations, PostgresMetricSnapshotRepository, PostgresSutStatSnapshotRepository, PostgresVirtualUserRepository}
import versola.loadgen.sut.PostgresSutStatsCapture
import versola.util.postgres.PostgresHikariDataSource
import zio.{ConfigProvider, Scope, ZIO, duration2DurationOps}

/** `role = coordinator`: wires the store, builds the plan service and starts its two timers
  * (versola-loadgen-dev-spec.md §12).
  *
  * Unlike the other roles this one does not take over `run` -- the coordinator *is* an HTTP
  * server, so it hands its service back to `Main`, which mounts [[CoordinatorRoutes]] on
  * `VersolaApp`'s. That is what gives it the same probe surface, the same graceful shutdown and
  * the same `/metrics` scrape contract as the SUT it is measuring, for free.
  */
object Coordinator:

  def make(config: LoadgenConfig): ZIO[Scope & ConfigProvider, Throwable, CoordinatorService] =
    for
      xa <- storeTransactor
      service <- CoordinatorService
        .make(
          config = config,
          users = PostgresVirtualUserRepository(xa),
          snapshots = PostgresMetricSnapshotRepository(xa),
          rebalancer = PostgresShardRebalancer(xa),
          // Absent for a coordinator that was given no SUT credentials, which is a deployment and
          // not a mistake: the report then carries every section but §3's.
          sutStats = config.sutStats.map: stats =>
            PostgresSutStatsCapture(stats.databases, PostgresSutStatSnapshotRepository(xa)),
        )
        .mapError(InvalidCoordinatorConfig(_))
      _ <- service.run
      _ <- ZIO.logInfo(
        s"Coordinator ready for campaign '${config.campaign.name}'; " +
          s"drivers poll every ${config.coordinator.pollInterval.render}",
      )
      _ <- ZIO.logInfo(
        config.sutStats match
          case Some(stats) =>
            s"pg_stat_* snapshots will bracket the campaign for ${stats.databases.map(_.name).mkString(", ")}"
          case None =>
            "No 'sut-stats' block; the campaign report will carry no database section",
      )
      _ <- ZIO.foreachDiscard(config.sutStats.toList.flatMap(stats => SutStatsConfig.clusterGroups(stats.databases))):
        group =>
          ZIO.logWarning(
            s"sut-stats.databases [${group.mkString(", ")}] share one Postgres cluster: their " +
              "pg_stat_wal/pg_stat_checkpointer/pg_stat_io figures will be identical and reported " +
              "under every name in the group, so summing that section across databases double-counts it",
          )
    yield service

  /** Migrated, not merely validated, for the one campaign that has no seed step: the registration
    * ramp starts from an empty population (design doc §2.4), so the coordinator is the first
    * process to touch the store and `migrate = false` would make it *validate* a database that
    * does not exist yet. Flyway is idempotent, so a campaign that was seeded first is unaffected.
    */
  private def storeTransactor: ZIO[Scope & ConfigProvider, Throwable, TransactorZIO] =
    PostgresHikariDataSource
      .transactor(
        serviceName = Some("loadgen-coordinator"),
        migrate = true,
        validateOnMigrate = true,
        migrationLocations = Some(LoadgenMigrations.locations),
        configPath = Seq("store", "postgres"),
      )
      .build
      .map(_.get[TransactorZIO])

case class InvalidCoordinatorConfig(reason: String) extends RuntimeException(reason)
