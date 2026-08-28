package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.test.*

import java.time.OffsetDateTime

/** Exercises the listener against a real database, because everything it has to get right —
  * delivery, a connection dying, resubscribing — only exists at that boundary.
  */
object PostgresNotificationListenerSpec extends ZIOSpecDefault:

  private val channel = "listener_spec"

  private def notify(payload: String) =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"SELECT pg_notify($channel, $payload)".query[String].run()))

  /** Ends the listener's session from the server side, which is what a pooler recycling an
    * idle connection or a database restart looks like from the client.
    */
  private def killListener =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT pg_terminate_backend(pid) FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'""".query[Boolean].run(),
      )

  def spec = suite("PostgresNotificationListener")(
    test("delivers notifications, opening with the resubscribe that tells subscribers to reload") {
      for
        listener <- PostgresNotificationListener.make(List(channel))
        received <- listener.notifications.take(2).runCollect.fork
        _ <- notify("first").delay(500.millis)
        events <- received.join
      yield assertTrue(
        events(0) == NotificationEvent.Resubscribed,
        events(1).asInstanceOf[NotificationEvent.Received].notification.getParameter == "first",
      )
    },
    test("reconnects and resubscribes after the connection is dropped") {
      for
        listener <- PostgresNotificationListener.make(List(channel))
        received <- listener.notifications.take(4).runCollect.fork
        _ <- notify("before").delay(500.millis)
        _ <- killListener.delay(500.millis)
        // Retried until delivered: the reconnect is not instant, and a notification published
        // before the new LISTEN takes effect is genuinely lost — which is the whole reason a
        // subscriber reloads on Resubscribed rather than trusting the feed across a gap.
        _ <- notify("after").delay(200.millis).repeatUntilZIO(_ => received.poll.map(_.isDefined))
        events <- received.join
      yield assertTrue(
        events(0) == NotificationEvent.Resubscribed,
        events(1).asInstanceOf[NotificationEvent.Received].notification.getParameter == "before",
        events(2) == NotificationEvent.Resubscribed,
        events(3).asInstanceOf[NotificationEvent.Received].notification.getParameter == "after",
      )
    },
    test("keeps trying to reconnect however long the database stays away") {
      // An outage is not a reason to stop: a restart or a failover routinely lasts longer
      // than any total-elapsed bound worth setting, and a listener that gave up would go
      // permanently silent while looking healthy, with propagation quietly degrading to the
      // periodic catch-up.
      val attempts = 1000
      for
        delays <- PostgresNotificationListener.reconnectSchedule
          .delays
          .run(OffsetDateTime.now().nn, List.fill(attempts)(RuntimeException("connection refused")))
      yield assertTrue(
        // Still recurring after far more failures than any bounded schedule would allow.
        delays.size == attempts,
        // And waiting a bounded amount between them, rather than backing off forever. The
        // cap is the ceiling times the upper jitter factor, since `jittered` spreads a delay
        // over 0.8x-1.2x to keep replicas from reconnecting in lockstep.
        delays.forall(_ <= 10.seconds * 1.2),
      )
    },
    test("holds a quiet connection open on its heartbeat, and keeps that heartbeat to itself") {
      // Nothing is published here at all, so every poll comes back empty and the heartbeat is
      // the only thing keeping the connection alive. The liveness timeout is several
      // heartbeats short of the runtime, so a heartbeat that was not making the round trip
      // would fail the connection and show up below as a second Resubscribed.
      //
      // The poll timeout has to be shorter than the liveness timeout for any of that to be
      // reached: liveness is only evaluated when a poll returns, so at the 10s default the
      // first evaluation would land after this test had already finished.
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(
          config,
          List(channel),
          heartbeatInterval = 100.millis,
          livenessTimeout = 1.second,
          pollTimeout = 100.millis,
        )
        events <- listener.notifications.take(2).runCollect.timeout(4.seconds)
      yield assertTrue(
        // Timed out waiting for a second event rather than collecting one: the connection
        // stayed up across every liveness deadline in those four seconds, and the heartbeats
        // that kept it up are filtered out before a subscriber sees them.
        events.isEmpty,
      )
    },
    test("replaces a connection that stops delivering, without it ever failing") {
      // A heartbeat interval longer than the liveness timeout means none is ever sent, which
      // leaves the connection in the state this is about: open, never erroring, and no longer
      // carrying anything, exactly as a socket killed by a NAT or firewall idle timeout would
      // be. Repeated resubscribes are the listener noticing and reconnecting anyway, which is
      // what a subscriber needs in order to reload.
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(
          config,
          List(channel),
          heartbeatInterval = 1.hour,
          livenessTimeout = 300.millis,
          pollTimeout = 100.millis,
        )
        events <- listener.notifications.take(3).runCollect.timeout(30.seconds)
      yield assertTrue(events.exists(_.forall(_ == NotificationEvent.Resubscribed)))
    },
    test("keeps polling behind a subscriber slow to react to its own Resubscribed") {
      // The realistic version of a slow subscriber reloads from the database precisely when
      // it sees Resubscribed — the same database this listener depends on — which is the one
      // moment polling must not wait on it: starting it only after that handler returns would
      // stop the heartbeat from running exactly while the database is why it needs to.
      // livenessTimeout is short enough that if polling waited here anyway, the connection
      // would look quiet for far longer than livenessTimeout the moment the handler finally
      // returns, and get torn down for a reason that has nothing to do with it actually
      // failing. Not waiting keeps this a healthy connection the whole time, resubscribing
      // exactly once.
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(
          config,
          List(channel),
          heartbeatInterval = 200.millis,
          livenessTimeout = 1.second,
          pollTimeout = 100.millis,
        )
        resubscribes <- Ref.make(0)
        fiber <- listener.notifications.runForeach {
          case NotificationEvent.Resubscribed => resubscribes.update(_ + 1) *> ZIO.sleep(3.seconds)
          case _ => ZIO.unit
        }.fork
        _ <- ZIO.sleep(4.seconds)
        _ <- fiber.interrupt
        count <- resubscribes.get
      yield assertTrue(count == 1)
    },
    test("refuses to start when notifications cannot be delivered") {
      for
        config <- ZIO.service[PostgresConfig]
        // A URL that connects but cannot deliver is exactly what a transaction-mode pooler
        // gives you, and cannot be simulated here; an unreachable one at least proves the
        // check fails the layer rather than letting the service come up half-working.
        unreachable = config.copy(notificationsUrl = Some("jdbc:postgresql://127.0.0.1:1/auth"))
        result <- PostgresNotificationListener.make(List(channel)).provideSome[Scope](ZLayer.succeed(unreachable)).exit
      yield assertTrue(result.isFailure)
    },
  ).provideSome[Scope](PostgresSpec.transactor, PostgresSpec.config) @@ TestAspect.withLiveClock @@ TestAspect.sequential
