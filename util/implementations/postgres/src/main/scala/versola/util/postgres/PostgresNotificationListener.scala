package versola.util.postgres

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.{PGConnection, PGNotification}
import zio.*
import zio.stream.{Stream, ZStream}

/** A dedicated pooled connection parked on `LISTEN`, exposing everything Postgres pushes to
  * it as a stream. Shared by every service that propagates changes over `NOTIFY`, so the
  * subtleties below are handled in one place rather than per subscriber.
  */
class PostgresNotificationListener(conn: PGConnection, jdbcConn: java.sql.Connection):

  def notifications: Stream[Throwable, PGNotification] =
    ZStream
      .repeatZIO(
        // Blocks the dedicated LISTEN connection for up to NotificationTimeoutMillis waiting for a
        // notification (pgjdbc's own recommended pattern), instead of busy-polling every 100ms.
        // The underlying blocking socket read does not respond to a plain thread interrupt, so on
        // fiber interruption (e.g. graceful shutdown) we abort the connection instead of closing it:
        // `jdbcConn` is a Hikari-pooled proxy, and Connection#close() on it just returns the
        // (still-blocked-on) connection to the pool for reuse rather than terminating the physical
        // socket — it wouldn't reliably unblock the read, and worse, another caller could borrow the
        // same connection while our read is still in flight on it. Connection#abort(Executor) forcibly
        // terminates the physical connection and evicts it from the pool instead of returning it as
        // healthy, which is exactly what we need here. Runs on the blocking executor since abort() can
        // itself block briefly tearing down the socket.
        ZIO.attemptBlockingCancelable(
          Option(conn.getNotifications(PostgresNotificationListener.NotificationTimeoutMillis))
            .map(_.toList)
            .getOrElse(Nil),
        )(cancel = ZIO.attemptBlocking(jdbcConn.abort(PostgresNotificationListener.directExecutor)).ignore),
      )
      .flattenIterables

object PostgresNotificationListener:
  // pgjdbc's own getNotifications(timeout) example uses 10s; see
  // https://access.crunchydata.com/documentation/pgjdbc/42.1.1/listennotify.html
  private val NotificationTimeoutMillis = 10000

  // Connection#abort(Executor) requires an Executor to run its (usually trivial) teardown task on.
  // This path only runs on interruption/shutdown, so a same-thread executor is sufficient.
  private val directExecutor: java.util.concurrent.Executor = (r: Runnable) => r.run()

  /** Borrows one connection from the pool for the lifetime of the scope and issues `LISTEN`
    * for each channel on it. The connection is never returned to general use: it stays parked
    * on the blocking read in [[PostgresNotificationListener.notifications]].
    */
  def make(channels: List[String]): ZIO[HikariDataSource & Scope, Throwable, PostgresNotificationListener] =
    for
      ds <- ZIO.service[HikariDataSource]
      jdbcConn <- ZIO.acquireRelease(ZIO.attempt(ds.getConnection()))(c => ZIO.attempt(c.close()).orDie)
      _ <- ZIO.attempt {
        val statement = jdbcConn.createStatement()
        try channels.foreach(channel => statement.execute(s"LISTEN $channel"))
        finally statement.close()
      }
      conn <- ZIO.attempt(jdbcConn.unwrap(classOf[PGConnection]))
    yield PostgresNotificationListener(conn, jdbcConn)
