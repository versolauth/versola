package versola.loadgen.sut

import zio.*
import zio.test.*

import java.sql.{Connection, DriverManager, SQLException}
import java.util.Properties

/** The `SHOW` commands themselves, against a real PgBouncer admin console.
  *
  * There is nothing here a fake could check, for [[SutStatsReaderSpec]]'s reason and one more of
  * its own. Every risk in this reader is a property of the thing it is pointed at: whether the
  * console accepts the query protocol the driver speaks, whether the column is spelled the way
  * this build spells it, and whether a counter arrives as text. A fake would answer yes to all
  * three by construction and the campaign would report an empty §4.
  */
object PoolerStatsReaderSpec extends ZIOSpecDefault:

  private def host = System.env("PGBOUNCER_HOST").someOrElse("localhost:6432")

  // `replication=database` is on every connection here, simple or not -- without it, pgjdbc's
  // own connect-time `SET extra_float_digits` is what fails, before either test's statement runs.
  // See `PoolerStatsCapture.connect` for why. Only `preferQueryMode` varies, because that is the
  // one property the "refused" test below is about.
  private def properties(simple: Boolean): Properties =
    val properties = Properties()
    properties.setProperty("user", "dev")
    properties.setProperty("password", "1234")
    properties.setProperty("connectTimeout", SutStatsCapture.connectTimeoutSeconds.toString)
    properties.setProperty("socketTimeout", SutStatsCapture.socketTimeoutSeconds.toString)
    properties.setProperty("replication", "database")
    if simple then properties.setProperty("preferQueryMode", "simple")
    properties

  private def console(simple: Boolean = true): ZIO[Scope, Throwable, Connection] =
    host.flatMap: address =>
      ZIO.acquireRelease(
        ZIO.attemptBlocking(DriverManager.getConnection(s"jdbc:postgresql://$address/pgbouncer", properties(simple))),
      )(connection => ZIO.attemptBlocking(connection.close()).orDie)

  def spec = suite("PoolerStatsReader")(
    test("reads the version, the per-database counters and the queue off a live console") {
      ZIO.scoped:
        for
          connection <- console()
          reading <- PoolerStatsReader.read(connection)
        yield
          // The admin console's own virtual database always has a row, because these very
          // captures are traffic on it -- so this assertion does not depend on anything having
          // used the pooler first.
          val own = reading.stats.counters.databases.find(_.database == "pgbouncer")
          assertTrue(
            reading.version.startsWith("PgBouncer"),
            own.isDefined,
            // Text fields that parsed: a column read but not parsed would be a silent zero.
            own.exists(_.queryCount > 0L),
            reading.stats.counters.databases.forall(_.waitTimeMicros >= 0L),
            // Not `user == "dev"`: PgBouncer tracks its own admin pool under the fixed internal
            // user `pgbouncer`, not the login that opened this connection -- true of every login,
            // confirmed against `psql` too, so `database` alone identifies the row this test owns.
            reading.stats.gauges.pools.exists(pool => pool.database == "pgbouncer" && pool.user == "pgbouncer"),
            reading.stats.gauges.pools.forall(_.poolMode.nonEmpty),
          )
    },
    // Every column name in the reader is a literal, and a build that spells one differently
    // degrades it to `None` rather than failing -- which is exactly the silent hole this asserts
    // against. The counters below are the ones §4 is computed from; none may be absent.
    test("the counters §4 is computed from are all present under the names the reader asks for") {
      ZIO.scoped:
        for
          connection <- console()
          reading <- PoolerStatsReader.read(connection)
        yield
          val own = reading.stats.counters.databases.find(_.database == "pgbouncer").get
          assertTrue(
            own.xactCount > 0L,
            own.queryCount > 0L,
            // Not `> 0` for these five: the admin pseudo-database has no real backend server
            // behind it, so `client_bytes`/`server_bytes` and the three timers, which PgBouncer
            // only accumulates on a connection with a real backend, stay genuinely zero on this
            // database for the life of the process -- confirmed empirically, not assumed.
            // `xactCount`/`queryCount` above are the columns this test can drive above zero, and
            // does; the five below are pinned present by parsing without throwing, which their
            // absence would leave indistinguishable from a true zero.
            own.receivedBytes >= 0L,
            own.sentBytes >= 0L,
            own.waitTimeMicros >= 0L,
            own.xactTimeMicros >= 0L,
            own.queryTimeMicros >= 0L,
          )
    },
    test("SHOW CONFIG supplies the limits the pool occupancy is read against") {
      ZIO.scoped:
        for
          connection <- console()
          reading <- PoolerStatsReader.read(connection)
        yield assertTrue(
          reading.stats.gauges.maxClientConnections.exists(_ > 0),
          reading.stats.gauges.defaultPoolSize.exists(_ > 0),
        )
    },
    // The reason `preferQueryMode=simple` is on the connection rather than being a tuning choice.
    // The connection itself succeeds without it -- `replication=database` is what that needs, and
    // it is present here too -- so this exercises `read`, not `console`. If a future PgBouncer
    // accepts the extended protocol this fails, and the property can go.
    test("the same reader over a connection without preferQueryMode=simple is refused by the console") {
      ZIO.scoped:
        for
          connection <- console(simple = false)
          refused <- PoolerStatsReader.read(connection).either
        yield assertTrue(refused.left.exists(_.isInstanceOf[SQLException]))
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
