package versola.util.postgres

import org.postgresql.{PGConnection, PGNotification}
import zio.*
import zio.stream.{Stream, ZStream}

import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.time.Instant
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
class PostgresNotificationListener(
    config: PostgresConfig,
    channels: List[String],
    heartbeatInterval: Duration = PostgresNotificationListener.HeartbeatInterval,
    livenessTimeout: Duration = PostgresNotificationListener.LivenessTimeout,
):
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
    *
    * A connection that stops delivering without ever failing is treated as the same event,
    * because that is what it is to a subscriber. [[heartbeat]] is what tells one apart from a
    * database that simply has nothing to say.
    */
  def notifications: Stream[Throwable, NotificationEvent] =
    ZStream
      .scoped(connect)
      .flatMap: session =>
        ZStream.succeed(NotificationEvent.Resubscribed) ++ read(session)
      .retry(reconnectSchedule)

  private def connect: ZIO[Scope, Throwable, Session] =
    for
      connection <- open
      // Unique per connection: what comes back on this channel proves that this session is
      // being delivered to, not that some other replica's session is.
      heartbeatChannel = s"versola_listener_heartbeat_${java.util.UUID.randomUUID().toString.replace("-", "_")}"
      _ <- ZIO.attemptBlocking(execute(connection, (heartbeatChannel :: channels).map(channel => s"LISTEN $channel")))
      pg <- ZIO.attempt(connection.unwrap(classOf[PGConnection]))
      now <- Clock.instant
      delivered <- Ref.make(now)
      beat <- Ref.make(now)
      _ <- DbMetrics.notificationListenerConnected(true)
      _ <- ZIO.addFinalizer(DbMetrics.notificationListenerConnected(false))
      _ <- ZIO.logInfo(s"Listening for notifications on ${channels.mkString(", ")}")
    yield Session(connection, pg, heartbeatChannel, delivered, beat)

  private def open: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(ZIO.attemptBlocking(PostgresNotificationListener.open(config)))(connection =>
      ZIO.attemptBlocking(connection.close()).ignoreLogged,
    )

  private def read(session: Session): Stream[Throwable, NotificationEvent] =
    ZStream
      .repeatZIO(poll(session))
      .flattenIterables
      // The heartbeat is this listener talking to itself. It proves the connection is live and
      // says nothing about the data, so subscribers never see it and it is not counted as a
      // notification received.
      .filter(_.getName != session.heartbeatChannel)
      .tap(_ => DbMetrics.notificationReceived)
      .map(NotificationEvent.Received(_))
      .tapError:
        case silent: SilentConnection =>
          ZIO.logWarningCause("Notification connection went silent; reconnecting", Cause.fail(silent)) *>
            DbMetrics.notificationListenerWentSilent *> DbMetrics.notificationListenerReconnected
        case cause =>
          ZIO.logWarningCause("Notification connection lost; reconnecting", Cause.fail(cause)) *>
            DbMetrics.notificationListenerReconnected

  /** One wait for notifications, and the liveness bookkeeping that goes with an empty one.
    *
    * Blocks for up to NotificationTimeoutMillis waiting for a notification (pgjdbc's own
    * recommended pattern) instead of busy-polling. The blocking socket read does not respond
    * to a plain thread interrupt, so on fiber interruption (graceful shutdown, or the retry
    * above tearing this attempt down) the connection is closed out from under the read by the
    * finalizer in `open`, which is what unblocks it.
    */
  private def poll(session: Session): Task[List[PGNotification]] =
    for
      notifications <- ZIO.attemptBlockingCancelable(
        Option(session.pg.getNotifications(NotificationTimeoutMillis)).map(_.toList).getOrElse(Nil),
      )(cancel = ZIO.unit)
      now <- Clock.instant
      _ <- if notifications.nonEmpty then session.delivered.set(now) else heartbeat(session, now)
    yield notifications

  /** Proves the connection still delivers, by giving it something to deliver.
    *
    * Nothing arriving is the ambiguous case: a database with nothing to say and a connection
    * that has stopped carrying anything look identical from here, and the second one produces
    * no error to react to. A socket killed by a NAT or firewall idle timeout is never closed
    * from either end, so the read below simply blocks forever on a path nothing will ever
    * write to again; `tcpKeepAlive` is set, but when it notices is the operating system's
    * default, which is measured in hours on a stock Linux.
    *
    * So the listener notifies its own channel, and requires it back. Silence past
    * [[livenessTimeout]] is taken as the connection being gone and fails the stream, which
    * puts it through the same reconnect and [[NotificationEvent.Resubscribed]] path as a
    * connection that dropped loudly.
    *
    * Sent from this fiber, in between reads, rather than from a second fiber or a second
    * connection: a pgjdbc connection is not safe to use from two threads at once, and one
    * that is blocked in `getNotifications` is exactly the case that would break.
    */
  private def heartbeat(session: Session, now: Instant): Task[Unit] =
    for
      delivered <- session.delivered.get
      _ <- ZIO.when(now.isAfter(delivered.plus(livenessTimeout)))(ZIO.fail(SilentConnection(livenessTimeout)))
      due <- session.beat.modify(sent => if now.isAfter(sent.plus(heartbeatInterval)) then (true, now) else (false, sent))
      _ <- ZIO.when(due)(ZIO.attemptBlocking(execute(session.connection, List(s"NOTIFY ${session.heartbeatChannel}"))))
    yield ()

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

  /** How often an otherwise idle connection is asked to prove itself, and how long it may
    * stay silent before it is replaced.
    *
    * The timeout is a multiple of the interval rather than equal to it, so a single heartbeat
    * lost to a slow moment is not read as a dead connection. Both are only checked when a
    * read comes back empty, so what either one actually resolves to is the next read
    * boundary. What the gap costs is bounded anyway: a subscriber's own periodic
    * reconciliation is what covers the interval, and this only decides how quickly the push
    * path comes back.
    */
  private val HeartbeatInterval = 30.seconds
  private val LivenessTimeout = 90.seconds

  /** A connection that is open, has never failed, and is no longer delivering. */
  private final class SilentConnection(timeout: Duration)
      extends RuntimeException(
        s"No notification on this connection in $timeout, including this listener's own heartbeat",
      )

  /** The listener's connection, and what has to be remembered per connection to tell a quiet
    * one from a dead one. Replaced wholesale on every reconnect, along with the timers.
    */
  private final case class Session(
      connection: Connection,
      pg: PGConnection,
      heartbeatChannel: String,
      delivered: Ref[Instant],
      beat: Ref[Instant],
  )

  /** Backs off up to [[ReconnectMaxBackoff]] between attempts, and never stops attempting.
    *
    * The cap is on the wait, not on how long reconnecting may go on for. Bounding the latter
    * would mean an outage that outlasts it leaves the listener permanently silent, with
    * propagation quietly falling back to the periodic catch-up — and a Postgres restart or a
    * failover routinely outlasts any bound worth setting. There is no number of failures
    * after which giving up is better than waiting: the connection is how this replica hears
    * about revocations at all.
    */
  private[postgres] val reconnectSchedule: Schedule[Any, Any, Any] =
    (Schedule.exponential(ReconnectMinBackoff) || Schedule.spaced(ReconnectMaxBackoff)).jittered

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

  /** Checks that notifications are deliverable at all, on a separate short-lived connection
    * so a subscriber cannot miss a real one arriving while the check is in progress, and
    * returns a listener for the given channels. Opening the real connection and issuing
    * `LISTEN` is not done here: that happens lazily, inside [[PostgresNotificationListener.notifications]],
    * and again on every reconnect for as long as the stream is consumed.
    */
  def make(channels: List[String]): ZIO[PostgresConfig & Scope, Throwable, PostgresNotificationListener] =
    for
      config <- ZIO.service[PostgresConfig]
      _ <- ZIO.scoped(verify(config))
    yield PostgresNotificationListener(config, channels)
