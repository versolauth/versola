package versola.util.postgres

import org.postgresql.{PGConnection, PGNotification}
import zio.*
import zio.stream.{Stream, ZStream}

import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.util.Properties

/** A dedicated connection parked on `LISTEN`, exposing everything Postgres pushes to it as a
  * stream. Shared by every service that propagates changes over `NOTIFY`, so the subtleties
  * below are handled in one place rather than per subscriber.
  *
  * The connection is opened directly rather than borrowed from the service's HikariCP pool.
  * A listener needs one physical backend to itself for its whole lifetime, which is the
  * opposite of what a pool is for: it would permanently occupy a slot of the budget sized for
  * request traffic, and it forces `leak-detection-threshold` off for the whole pool because a
  * connection held forever looks exactly like a leak. Owning the socket also means shutdown
  * can simply close it, instead of aborting a pooled proxy to avoid handing a connection
  * still blocked on a read back to the pool.
  *
  * Separating it also leaves room for a connection pooler in front of `url`: notifications
  * need a session that outlives a transaction, so [[PostgresConfig.notificationsUrl]] can
  * point past the pooler while everything else goes through it.
  */
class PostgresNotificationListener(config: PostgresConfig, channels: List[String]):
  import PostgresNotificationListener.*

  /** Notifications from every channel this listener subscribes to, reconnecting for as long
    * as the stream is consumed.
    *
    * A dropped connection is expected rather than exceptional \u2014 Postgres restarts, network
    * blips, and poolers that recycle idle server connections all produce one, and an idle
    * `LISTEN` connection is the first thing an idle timeout reaps. Whatever was published
    * while the connection was gone is not redelivered on reconnect, so each (re)connect opens
    * with [[NotificationEvent.Resubscribed]]: subscribers treat it as "your view may have
    * gaps" and reload from the table, which is the source of truth in every case here.
    */
  def notifications: Stream[Throwable, NotificationEvent] =
    ZStream
      .scoped(connect)
      .flatMap: session =>
        ZStream.succeed(NotificationEvent.Resubscribed) ++ read(session)
      .retry(Schedule.exponential(ReconnectMinBackoff).jittered.upTo(ReconnectMaxBackoff))

  private def connect: ZIO[Scope, Throwable, PGConnection] =
    for
      connection <- open
      _ <- ZIO.attemptBlocking(execute(connection, channels.map(channel => s"LISTEN $channel")))
      session <- ZIO.attempt(connection.unwrap(classOf[PGConnection]))
      _ <- DbMetrics.notificationListenerConnected(true)
      _ <- ZIO.addFinalizer(DbMetrics.notificationListenerConnected(false))
      _ <- ZIO.logInfo(s"Listening for notifications on ${channels.mkString(", ")}")
    yield session

  private def open: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(ZIO.attemptBlocking(PostgresNotificationListener.open(config)))(connection =>
      ZIO.attemptBlocking(connection.close()).ignoreLogged,
    )

  private def read(session: PGConnection): Stream[Throwable, NotificationEvent] =
    ZStream
      .repeatZIO(
        // Blocks for up to NotificationTimeoutMillis waiting for a notification (pgjdbc's own
        // recommended pattern) instead of busy-polling. The blocking socket read does not respond
        // to a plain thread interrupt, so on fiber interruption (graceful shutdown, or the retry
        // above tearing this attempt down) the connection is closed out from under the read by the
        // finalizer in `open`, which is what unblocks it.
        ZIO.attemptBlockingCancelable(
          Option(session.getNotifications(NotificationTimeoutMillis)).map(_.toList).getOrElse(Nil),
        )(cancel = ZIO.unit),
      )
      .flattenIterables
      .tap(_ => DbMetrics.notificationReceived)
      .map(NotificationEvent.Received(_))
      .tapError(cause => ZIO.logWarningCause("Notification connection lost; reconnecting", Cause.fail(cause)) *> DbMetrics.notificationListenerReconnected)

/** What a subscriber sees on the notification stream. */
enum NotificationEvent:
  /** The listener (re)subscribed. Anything published before this point may not have been
    * delivered, so a subscriber holding derived state should rebuild it from the database.
    */
  case Resubscribed

  /** A notification delivered by Postgres. */
  case Received(notification: PGNotification)

object PostgresNotificationListener:
  // pgjdbc's own getNotifications(timeout) example uses 10s; see
  // https://access.crunchydata.com/documentation/pgjdbc/42.1.1/listennotify.html
  private val NotificationTimeoutMillis = 10000

  private val ReconnectMinBackoff = 100.millis
  private val ReconnectMaxBackoff = 10.seconds

  /** How long the startup check waits for its own notification to come back. Generous: it
    * runs once, and a false failure here stops the service from starting.
    */
  private val VerifyTimeout = 10.seconds

  /** Opens the listener's own connection to [[PostgresConfig.notificationsUrl]], or to `url`
    * when it isn't set.
    *
    * `tcpKeepAlive` matters more here than anywhere else in the codebase: this connection is
    * silent for long stretches by design, so without it a connection dropped by a firewall or
    * NAT timeout leaves the read blocked forever on a socket nothing will ever write to,
    * which looks identical to "nothing has been revoked lately".
    */
  private def open(config: PostgresConfig): Connection =
    val properties = Properties()
    properties.setProperty("user", config.user)
    // Secret is an opaque Array[Byte] newtype (kept out of toString/logging); the driver needs
    // a plain String, so it's decoded back here at the point of use only.
    properties.setProperty("password", new String(config.password, StandardCharsets.UTF_8))
    properties.setProperty("tcpKeepAlive", "true")
    properties.setProperty("ApplicationName", "versola-notification-listener")
    DriverManager.getConnection(config.notificationsUrl.getOrElse(config.url), properties)

  private def execute(connection: Connection, statements: List[String]): Unit =
    val statement = connection.createStatement()
    try statements.foreach(statement.execute)
    finally statement.close()

  /** Proves that this connection can actually receive notifications, by sending itself one
    * (Postgres delivers a session its own notifications) and waiting for it to arrive.
    *
    * The failure this catches is a pooler sitting between the service and Postgres in
    * transaction mode: `LISTEN` returns success, the session it applied to goes back to the
    * pool, and no notification is ever delivered. Nothing errors, so without this check the
    * only symptom is propagation quietly degrading to whatever periodic reload the subscriber
    * happens to run \u2014 a security control weakening with no signal. Better to refuse to start.
    */
  private def verify(config: PostgresConfig): ZIO[Scope, Throwable, Unit] =
    val channel = s"versola_listener_check_${java.util.UUID.randomUUID().toString.replace("-", "_")}"
    ZIO.acquireRelease(ZIO.attemptBlocking(open(config)))(c => ZIO.attemptBlocking(c.close()).ignoreLogged).flatMap:
      connection =>
        for
          _ <- ZIO.attemptBlocking(execute(connection, List(s"LISTEN $channel", s"NOTIFY $channel")))
          session <- ZIO.attempt(connection.unwrap(classOf[PGConnection]))
          delivered <- ZIO
            .attemptBlocking(Option(session.getNotifications(VerifyTimeout.toMillis.toInt)).exists(_.nonEmpty))
            .timeoutTo(false)(identity)(VerifyTimeout)
          _ <- ZIO.unless(delivered):
            ZIO.fail(
              IllegalStateException(
                s"LISTEN/NOTIFY is not working on ${config.notificationsUrl.getOrElse(config.url)}: this connection " +
                  "did not receive its own notification. The usual cause is a connection pooler in transaction " +
                  "mode, which cannot support LISTEN; point postgres.notifications-url straight at Postgres, or " +
                  "run the pooler in session mode.",
              ),
            )
        yield ()

  /** Opens a connection of this listener's own for the lifetime of the scope and issues
    * `LISTEN` for each channel on it, after checking that notifications are deliverable at
    * all. The check runs on a separate short-lived connection so a subscriber cannot miss a
    * real notification arriving while it is in progress.
    */
  def make(channels: List[String]): ZIO[PostgresConfig & Scope, Throwable, PostgresNotificationListener] =
    for
      config <- ZIO.service[PostgresConfig]
      _ <- ZIO.scoped(verify(config))
    yield PostgresNotificationListener(config, channels)
