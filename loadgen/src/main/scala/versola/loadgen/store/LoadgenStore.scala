package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.HikariDataSource
import versola.loadgen.config.StoreConfig
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*

/** The emulator's own store, wired end to end: pool and schema, the four repositories, the
  * deferred sink, and the write-behind buffer in front of it.
  *
  * `configPath` is `store.postgres`, not the top-level `postgres { }` every service uses, and
  * `migrationLocations` is [[LoadgenMigrations.locations]] rather than the auto-detected
  * `<service>/implementations/postgres/migrations` -- see both of those for why. Getting either
  * wrong fails silently rather than loudly: the wrong config path is a boot error, but the wrong
  * locations mean Flyway finds nothing to apply, reports success, and the first query fails
  * against a table that was never created.
  */
object LoadgenStore:

  val configPath: Seq[String] = Seq("store", "postgres")

  /** @param migrate
    *   whether this process applies the schema. The coordinator does; a driver does not (eight
    *   of them racing Flyway on startup is a lock convoy at best). `false` still validates the
    *   schema against these migrations rather than skipping Flyway -- see
    *   `PostgresHikariDataSource.layer`.
    */
  def transactor(
      migrate: Boolean
  ): ZLayer[Scope & ConfigProvider, Throwable, TransactorZIO & HikariDataSource & PostgresConfig] =
    PostgresHikariDataSource.transactor(
      serviceName = Some("loadgen-store"),
      migrate = migrate,
      migrationLocations = Some(LoadgenMigrations.locations),
      configPath = configPath,
    )

  val repositories: ZLayer[
    TransactorZIO,
    Throwable,
    VirtualUserRepository & DeviceSessionRepository & EventRepository & MetricSnapshotRepository,
  ] =
    PostgresVirtualUserRepository.live ++
      PostgresDeviceSessionRepository.live ++
      PostgresEventRepository.live ++
      PostgresMetricSnapshotRepository.live

  /** Everything a driver needs from this package, given the config and a scope. */
  def live(migrate: Boolean): ZLayer[
    Scope & ConfigProvider & StoreConfig,
    Throwable,
    VirtualUserRepository & DeviceSessionRepository & EventRepository & MetricSnapshotRepository &
      WriteBehindBuffer,
  ] =
    transactor(migrate) >>> repositories >+> (PostgresDeferredWriteSink.live >>> WriteBehindBuffer.layer)
