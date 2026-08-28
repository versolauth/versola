package versola.util.postgres

import org.postgresql.{PGConnection, PGNotification}
import zio.*
import zio.stream.{Stream, Take, ZStream}

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
    pollTimeout: Duration = PostgresNotificationListener.PollTimeout,
    queueCapacity: Int = PostgresNotificationListener.QueueCapacity,
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
      .flatMap: (session, queue) =>
        ZStream.succeed(NotificationEvent.Resubscribed) ++ read(session, queue)
      .retry(reconnectSchedule)

  /** Opens the connection, `LISTEN`s, and starts polling it on a fiber of its own before
    * this returns — not lazily on the first pull of the resulting stream.
    *
    * That distinction matters because the first thing downstream ever does with a fresh
    * connection is react to [[NotificationEvent.Resubscribed]], and in practice that handler
    * is a synchronous reload against the same database this listener depends on. Deferring
    * the fork until [[read]] is first pulled would start polling only once that handler
    * returns, which is exactly backwards during the trouble this exists to survive: the
    * heartbeat would not run precisely while the database is why it needs to.
    */
  private def connect: ZIO[Scope, Throwable, (Session, Queue[Take[Throwable, PGNotification]])] =
    for
      connection <- open
      // Unique per connection: what comes back on this channel proves that this session is
      // being delivered to, not that some other replica's session is.
      heartbeatChannel = s"versola_listener_heartbeat_${java.util.UUID.randomUUID().toString.replace("-", "_")}"
      _ <- ZIO.attemptBlocking(execute(connection, (heartbeatChannel :: channels).map(channel => s"LISTEN $channel")))
      pg <- ZIO.attempt(connection.unwrap(classOf[PGConnection]))
      now <- Clock.instant
      // None until something actually round-trips: see DbMetrics.notificationListenerConnected
      // for why LISTEN succeeding is not that proof.
      delivered <- Ref.make(Option.empty[Instant])
      // Already due, rather than due after a full heartbeatInterval: a freshly opened
      // connection is proven, or shown broken, within the first poll cycle instead of only
      // after sitting quiet for one interval first.
      beat <- Ref.make(now.minus(heartbeatInterval))
      _ <- ZIO.addFinalizer(DbMetrics.notificationListenerConnected(false))
      _ <- ZIO.logInfo(s"Listening for notifications on ${channels.mkString(", ")}")
      session = Session(connection, pg, heartbeatChannel, now, delivered, beat)
      // Bounded and dropping rather than unbounded: a subscriber stuck behind its own slow
      // reload is exactly the situation this fiber exists to keep polling through, so it
      // must never suspend trying to hand off a result, and a queue that backpressures on a
      // full buffer would do just that. Dropping the newest arrival instead of growing
      // forever costs nothing this listener's subscribers do not already tolerate: every one
      // of them treats a missed notification as covered by its own periodic reload against
      // the same table, the same tolerance a lost connection relies on.
      queue <- Queue.dropping[Take[Throwable, PGNotification]](queueCapacity)
      _ <- pollLoop(session, queue).forkScoped
    yield (session, queue)

  private def open: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(ZIO.attemptBlocking(PostgresNotificationListener.open(config)))(connection =>
      ZIO.attemptBlocking(connection.close()).ignoreLogged,
    )

  /** The queue [[connect]] already has [[pollLoop]] filling, turned into what a subscriber
    * sees. The queue is unbounded because a bounded one would eventually apply the same
    * backpressure the fiber in `connect` exists to avoid: it would just move where a slow
    * subscriber stalls polling from "immediately" to "once the queue fills up".
    */
  private def read(session: Session, queue: Queue[Take[Throwable, PGNotification]]): Stream[Throwable, NotificationEvent] =
    ZStream
      .fromQueue(queue)
      .flattenTake
      // The heartbeat is this listener talking to itself. It proves the connection is live
      // and says nothing about the data, so subscribers never see it and it is not counted
      // as a notification received.
      .filter(_.getName != session.heartbeatChannel)
      .tap(_ => DbMetrics.notificationReceived)
      .map(NotificationEvent.Received(_))
      // Counted apart from a loud failure rather than as well as one: a connection that
      // went silent is the only one of the two that points at the network path rather than
      // at the database, and summing them would hide exactly that.
      .tapError:
        case silent: SilentConnection =>
          ZIO.logWarningCause("Notification connection went silent; reconnecting", Cause.fail(silent)) *>
            DbMetrics.notificationListenerWentSilent
        case cause =>
          ZIO.logWarningCause("Notification connection lost; reconnecting", Cause.fail(cause)) *>
            DbMetrics.notificationListenerReconnected

  private def pollLoop(session: Session, queue: Queue[Take[Throwable, PGNotification]]): UIO[Unit] =
    poll(session).foldCauseZIO(
      // A failure always gets in: `Queue.dropping` only drops what a full buffer has no room
      // for, and this is the one element that must never be the one dropped, or a subscriber
      // stuck behind a slow reload would never learn the connection under it died either.
      cause => queue.offer(Take.failCause(cause)).unit,
      notifications => offer(queue, notifications) *> pollLoop(session, queue),
    )

  private def offer(queue: Queue[Take[Throwable, PGNotification]], notifications: List[PGNotification]): UIO[Unit] =
    if notifications.isEmpty then ZIO.unit
    else
      queue.offer(Take.chunk(Chunk.fromIterable(notifications))).flatMap: accepted =>
        ZIO.unless(accepted):
          ZIO.logWarning(s"Notification queue full; dropping ${notifications.size} notification(s)") *>
            DbMetrics.notificationListenerQueueOverflow
        .unit

  /** One wait for notifications, and the liveness bookkeeping that goes with an empty one.
    *
    * Blocks for up to [[pollTimeout]] waiting for a notification (pgjdbc's own
    * recommended pattern) instead of busy-polling. The blocking socket read does not respond
    * to a plain thread interrupt, so on fiber interruption (graceful shutdown, or the retry
    * above tearing this attempt down) the connection is closed out from under the read by the
    * finalizer in `open`, which is what unblocks it.
    */
  private def poll(session: Session): Task[List[PGNotification]] =
    for
      notifications <- ZIO.attemptBlockingCancelable(
        Option(session.pg.getNotifications(pollTimeout.toMillis.toInt)).map(_.toList).getOrElse(Nil),
      )(cancel = ZIO.unit)
      now <- Clock.instant
      _ <- if notifications.nonEmpty then proven(session, now) else heartbeat(session, now)
    yield notifications

  /** Records that something (a real notification or this listener's own heartbeat, both
    * arrive the same way) has round-tripped, and flips the gauge the first time that happens
    * on this connection rather than optimistically at connect.
    */
  private def proven(session: Session, now: Instant): UIO[Unit] =
    session.delivered.get.flatMap:
      case Some(_) => session.delivered.set(Some(now))
      case None    => session.delivered.set(Some(now)) *> DbMetrics.notificationListenerConnected(true)

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
      // Nothing has round-tripped since connecting: measured from connect, not from now, so
      // a session that never manages a single delivery still gets exactly livenessTimeout
      // before it is given up on, the same as one that has been failing for a while.
      _ <- ZIO.when(now.isAfter(delivered.getOrElse(session.connectedAt).plus(livenessTimeout)))(
        ZIO.fail(SilentConnection(livenessTimeout)),
      )
      due <- session.beat.modify(sent => if now.isAfter(sent.plus(heartbeatInterval)) then (true, now) else (false, sent))
      // A write that fails outright is the same silent connection revealing itself through
      // the socket timeout on this send instead of through the read-side deadline above, so
      // it is folded into the same failure rather than left to read as a loud one.
      _ <- ZIO.when(due)(
        ZIO
          .attemptBlocking(execute(session.connection, List(s"NOTIFY ${session.heartbeatChannel}")))
          .mapError(cause => SilentConnection(livenessTimeout, Some(cause))),
      )
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
  private val PollTimeout = 10.seconds

  /** Bounds every read on this connection that isn't the wait for notifications.
    *
    * `getNotifications` takes its own timeout, and pgjdbc restores this one underneath it
    * afterwards, so what this covers is the statements: `LISTEN` at connect time and the
    * heartbeat's `NOTIFY`. Both are sent on the same connection the liveness check runs on,
    * and a write to a black-holed socket is accepted locally and then waits forever for a
    * response, so without a bound here the heartbeat is where a dead connection would hang
    * instead of the read it was added to catch.
    */
  private val SocketTimeout = 30.seconds

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

  /** Caps the backlog between the polling fiber and a subscriber that has fallen behind.
    * Payloads here are a few hundred bytes each (see [[PostgresRevocationNotifications]]),
    * so this bounds the worst case at a few megabytes, not a number picked to match any
    * expected burst size: staying bounded at all is the property that matters, since falling
    * behind this far already means the subscriber is relying on its periodic reload anyway.
    */
  private val QueueCapacity = 10_000

  /** A connection that is open, has never failed, and is no longer delivering — whether that
    * shows up as the read-side liveness deadline elapsing, or as the heartbeat's own write
    * failing outright, which a socket timeout on a NAT- or firewall-blackholed connection
    * does before the deadline otherwise would.
    */
  private final class SilentConnection(timeout: Duration, cause: Option[Throwable] = None)
      extends RuntimeException(
        s"No notification on this connection in $timeout, including this listener's own heartbeat",
        cause.orNull,
      )

  /** The listener's connection, and what has to be remembered per connection to tell a quiet
    * one from a dead one. Replaced wholesale on every reconnect, along with the timers.
    */
  private final case class Session(
      connection: Connection,
      pg: PGConnection,
      heartbeatChannel: String,
      connectedAt: Instant,
      delivered: Ref[Option[Instant]],
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
    properties.setProperty("socketTimeout", SocketTimeout.toSeconds.toString)
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
