package versola.util.postgres

import zio.Duration

/** Configuration for the PostgreSQL HikariCP connection pool.
  *
  * All fields are required (no defaults) — every service must set them
  * explicitly in its `postgres { }` config block so that the connection
  * budget is coordinated with `max_connections` across all service instances.
  *
  * @param url
  *   JDBC URL of the database
  * @param user
  *   Database user
  * @param password
  *   Database password
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
  *   logged (HikariCP `leakDetectionThreshold`). Set to `0 seconds` to disable —
  *   required for services holding a long-lived connection (e.g. central's
  *   `LISTEN` connection), otherwise it is reported as a false-positive leak.
  */
case class PostgresConfig(
    url: String,
    user: String,
    password: String,
    maximumPoolSize: Int,
    minimumIdle: Int,
    connectionTimeout: Duration,
    maxLifetime: Duration,
    leakDetectionThreshold: Duration,
)
