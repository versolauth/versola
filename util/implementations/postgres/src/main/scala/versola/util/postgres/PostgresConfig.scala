package versola.util.postgres

import versola.util.Secret
import zio.Duration
import zio.config.magnolia.DeriveConfig

/** Configuration for the PostgreSQL HikariCP connection pool.
  *
  * Every field except `notifications-url` and `pool-metrics-interval` is required (no defaults) —
  * every service must set them explicitly in its `postgres { }` config block so that the
  * connection budget is coordinated with `max_connections` across all service instances.
  *
  * @param url
  *   JDBC URL of the database
  * @param notificationsUrl
  *   JDBC URL used only for the `LISTEN` connection (see [[PostgresNotificationListener]]),
  *   falling back to `url` when unset. This exists so `url` can point at a connection pooler
  *   while notifications keep a direct route to Postgres: a pooler in transaction mode
  *   multiplexes sessions across backends, which breaks `LISTEN` — silently, since the
  *   statement still succeeds and notifications simply never arrive.
  * @param user
  *   Database user
  * @param password
  *   Database password. Wrapped in [[Secret]] (an opaque `Array[Byte]` newtype) so it
  *   doesn't leak through `toString`/logging of the config object.
  * @param maximumPoolSize
  *   Maximum number of connections in the pool (HikariCP `maximumPoolSize`)
  * @param minimumIdle
  *   Minimum number of idle connections HikariCP tries to maintain. Recommended
  *   to equal `maximumPoolSize` for a fixed-size pool.
  * @param connectionTimeout
  *   Maximum time to wait for a connection from the pool before failing
  *   (HikariCP `connectionTimeout`)
  * @param maxLifetime
  *   Maximum lifetime of a connection in the pool (HikariCP `maxLifetime`).
  *   Should be shorter than any database/infra-side connection timeout.
  * @param leakDetectionThreshold
  *   Amount of time a connection can be out of the pool before a leak warning is
  *   logged (HikariCP `leakDetectionThreshold`). Set to `0 seconds` to disable.
  * @param poolMetricsInterval
  *   How often the pool's own readings — size, busy, idle, threads waiting, connection-acquisition
  *   wait time, connection timeouts — are published to the `db_client_connection_*` metrics of
  *   [[DbMetrics]]. Absent means the pool publishes none of them and HikariCP is left without a
  *   `MetricsTrackerFactory`, which is the state every service was in before this existed.
  *
  *   Opt-in rather than always-on because these series are not free in the only dimension that is
  *   hard to take back: a scrape surface. Turning them on for auth/central/edge adds a metric
  *   family to endpoints that alerts and boards are already written against, and the process that
  *   wants them (a load campaign reading pool saturation against `max_connections`) is not the
  *   process that pays for them. The interval is the knob rather than a boolean because it is the
  *   one number an operator actually has to choose — a gauge written less often than Prometheus
  *   scrapes reports a stale peak, and a gauge written far more often than that costs the pod
  *   without telling anyone anything.
  */
case class PostgresConfig(
    url: String,
    notificationsUrl: Option[String],
    user: String,
    password: Secret,
    maximumPoolSize: Int,
    minimumIdle: Int,
    connectionTimeout: Duration,
    maxLifetime: Duration,
    leakDetectionThreshold: Duration,
    poolMetricsInterval: Option[Duration],
)

/** The DB password is a plain string in HOCON (not base64), so it's decoded via
  * `Secret.fromString` rather than the base64url decoders used elsewhere for other secrets.
  *
  * Declared top-level (not inside `object PostgresConfig`) so every file in this package —
  * `PostgresHikariDataSource` and the test specs — picks it up via ordinary same-package
  * visibility, without needing an explicit import.
  */
given DeriveConfig[Secret] = DeriveConfig[String].map(Secret.fromString)
