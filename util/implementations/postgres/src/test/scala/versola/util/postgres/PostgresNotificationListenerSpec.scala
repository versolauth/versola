package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.metrics.*
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

  /** The backend pid(s) currently serving the listener's own connection, independent of
    * anything the listener's public API exposes -- proof that a reconnect actually opened a
    * new session, rather than an inference from what a subscriber happened to observe.
    */
  private def listenerPid =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"SELECT pid FROM pg_stat_activity WHERE application_name = 'versola-notification-listener'"
          .query[Int]
          .run(),
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
    test("tells a black-holed heartbeat write apart from an ordinary loud one") {
      // The heartbeat's own NOTIFY relabels a failure as the connection going silent only for
      // the one case that actually is: a socket timing out on a write nothing will ever get a
      // response to. Anything else that can fail a NOTIFY -- an admin shutdown, a terminated
      // backend -- reached the driver the way a loud failure ordinarily does, and must stay
      // one, or a real database incident gets counted and alerted on as a network problem.
      assertTrue(
        PostgresNotificationListener.isSocketTimeout(java.net.SocketTimeoutException("Read timed out")),
        // Wrapped by an intermediate exception, the way pgjdbc wraps a socket-level failure
        // in a PSQLException: still found, because it is the chain that is classified.
        PostgresNotificationListener.isSocketTimeout(
          RuntimeException("wrapped", java.net.SocketTimeoutException("Read timed out")),
        ),
        // A loud failure with no timeout anywhere in its chain is left alone.
        !PostgresNotificationListener.isSocketTimeout(RuntimeException("FATAL: terminating connection due to administrator command")),
        !PostgresNotificationListener.isSocketTimeout(RuntimeException("connection closed")),
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
        connected = Metric
          .gauge("db_notification_listener_connected")
          .tagged(MetricLabel("db_system", "postgresql"))
        events <- Ref.make(Chunk.empty[NotificationEvent])
        fiber <- listener.notifications.runForeach(event => events.update(_ :+ event)).fork
        _ <- ZIO.sleep(4.seconds)
        // Read before the interrupt, which tears the connection down and sets this back to 0.
        proven <- connected.value
        _ <- fiber.interrupt
        collected <- events.get
      yield assertTrue(
        // Asserted as what did arrive rather than as a timeout: nothing arriving is also what
        // a connection that never came up at all looks like, so an empty collection would
        // have passed for a listener that spent the whole four seconds failing to connect.
        //
        // Exactly one Resubscribed: it came up once, and stayed up across every liveness
        // deadline in four seconds — at a 100ms poll and a 1s timeout, a heartbeat that was
        // not making the round trip would have failed the connection and shown up here as a
        // second one.
        collected == Chunk(NotificationEvent.Resubscribed),
        // And the gauge only flips on something actually round-tripping. Nothing is published
        // in this test, so the heartbeat is the only thing that can have flipped it, which is
        // the round trip asserted directly rather than inferred from the absence of a
        // reconnect. Read after a four-second window rather than at the moment of a flip, so
        // there is nothing here to race.
        proven.value == 1.0,
      )
    },
    test("replaces a connection that stops delivering, without it ever failing") {
      // A heartbeat interval longer than the liveness timeout does not mean no heartbeat is
      // ever sent: `beat` starts already-due, so each fresh connection gets exactly one right
      // after connecting. It means no *second* one arrives before livenessTimeout elapses, so
      // every connection goes quiet after that first round trip and is replaced, exactly as a
      // socket killed by a NAT or firewall idle timeout would be. Repeated resubscribes are
      // the listener noticing and reconnecting anyway, which is what a subscriber needs in
      // order to reload.
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
    test("bounds memory by notification, not by the batch Postgres happens to hand back") {
      // Sent as fast as this fiber can issue them, with a poll timeout long enough to cover
      // the whole burst, rather than the spaced-out sends the next test uses to force one
      // notification per poll cycle: the point here is specifically what happens when several
      // arrive in the same getNotifications() call, since Queue.dropping bounds the number of
      // elements offered to it, not what those elements contain. A single element wrapping
      // Postgres's whole batch would let a burst this size occupy one of deliveryCapacity
      // slots regardless of how many notifications were in it, so the bound this exists to
      // enforce would not hold. Offered individually -- both into the queue daemon fills and,
      // from there, into delivery -- capacity elements are accepted and the rest are rejected
      // regardless of how pgjdbc happened to batch them, which is what makes the dropped count
      // below exact rather than just a lower bound.
      val overflow =
        Metric.counter("db_notification_listener_queue_overflow_total").tagged(MetricLabel("db_system", "postgresql"))
      val sent = 50
      val capacity = 3
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(config, List(channel), pollTimeout = 5.seconds, deliveryCapacity = capacity)
        before <- overflow.value
        fiber <- listener.notifications.runForeach {
          case NotificationEvent.Resubscribed => ZIO.never
          case _ => ZIO.unit
        }.fork
        _ <- ZIO.sleep(300.millis)
        _ <- ZIO.foreachDiscard(1 to sent)(i => notify(s"burst-$i"))
        _ <- ZIO.sleep(1.second)
        after <- overflow.value
        _ <- fiber.interrupt
      yield assertTrue(
        // Exact, not a lower bound: with a 5s poll timeout against a burst that lands well
        // inside it, every poll before the burst is drained comes back non-empty, so the
        // heartbeat never fires and never competes for a slot either.
        after.count - before.count == (sent - capacity).toDouble,
      )
    },
    test("drops rather than grows without bound when a subscriber never catches up") {
      // A subscriber stuck on the opening Resubscribed forever, rather than merely slow: it
      // never pulls again, so nothing but a bounded queue stands between daemon, which keeps
      // running regardless, and unbounded memory growth. deliveryCapacity is set far below
      // what gets published, and pollTimeout short enough that each notification lands in its
      // own poll cycle (spaced well past it), so each becomes a separate queued item instead
      // of batching into one and the overflow is forced deterministically rather than raced.
      val overflow =
        Metric.counter("db_notification_listener_queue_overflow_total").tagged(MetricLabel("db_system", "postgresql"))
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(config, List(channel), pollTimeout = 50.millis, deliveryCapacity = 2)
        before <- overflow.value
        fiber <- listener.notifications.runForeach {
          case NotificationEvent.Resubscribed => ZIO.never
          case _ => ZIO.unit
        }.fork
        _ <- ZIO.sleep(300.millis)
        _ <- ZIO.foreachDiscard(1 to 5)(i => notify(s"overflow-$i").delay(150.millis))
        _ <- ZIO.sleep(300.millis)
        after <- overflow.value
        _ <- fiber.interrupt
      yield assertTrue(
        // At least 3, not exactly: a queue slot can also be consumed by the listener's own
        // heartbeat (which starts already-due, so one always fires), and that consumption
        // legitimately competes with real notifications for the same bounded capacity.
        after.count - before.count >= 3.0,
      )
    },
    test("reconnects ahead of the backlog a subscriber has not caught up on") {
      // The two failure modes compound here: the subscriber is far enough behind to have
      // filled delivery, and then the connection under it dies. Both happen inside the
      // handler for the first Resubscribed, which is what fixes the ordering rather than
      // racing it: daemon keeps running regardless of that handler -- polling, and delivering
      // what it polls -- so delivery is full of the dying connection's backlog and the
      // connection is dead before the subscriber pulls again.
      //
      // What the subscriber must see on its next pull is the reconnect, not the leftovers of
      // the connection that died. Left in delivery, those leftovers would surface first, and
      // only once the subscriber had worked through a backlog it is by definition slow to
      // work through. daemon drains delivery on every failure for exactly this: nothing is
      // lost that the Resubscribed's own reload does not cover, and what the subscriber pulls
      // next is that Resubscribed, not what a connection already known to be dead sent before
      // it died.
      for
        config <- ZIO.service[PostgresConfig]
        listener = PostgresNotificationListener(config, List(channel), pollTimeout = 50.millis, deliveryCapacity = 2)
        events <- Ref.make(Chunk.empty[NotificationEvent])
        first <- Ref.make(true)
        fiber <- listener
          .notifications
          .runForeach: event =>
            events.update(_ :+ event) *> ZIO.when(event == NotificationEvent.Resubscribed):
              first.getAndSet(false).flatMap: isFirst =>
                ZIO.when(isFirst):
                  ZIO.foreachDiscard(1 to 5)(i => notify(s"backlog-$i").delay(150.millis)) *>
                    killListener *> ZIO.sleep(300.millis)
          .fork
        _ <- ZIO.sleep(5.seconds)
        _ <- fiber.interrupt
        collected <- events.get
      yield assertTrue(
        // Two of them, so the failure got out of a full queue at all: pollLoop stops after
        // the failure, so one that was dropped for want of room would leave the subscriber
        // waiting on a queue nothing will ever fill again.
        collected.count(_ == NotificationEvent.Resubscribed) == 2,
        // And immediately, with none of the dead connection's notifications in between.
        collected.take(2) == Chunk(NotificationEvent.Resubscribed, NotificationEvent.Resubscribed),
      )
    },
    test("reconnects even while a subscriber is stalled inside its own handler indefinitely") {
      // Not merely slow, as the tests above exercise, but never returning at all -- which is
      // exactly what a synchronous reload against a database already struggling enough to
      // have cost this listener its connection can do. Reconnecting cannot be conditional on
      // that handler ever returning, and this is verified independent of what a subscriber
      // observes, since a subscriber stuck like this by definition never observes anything
      // again: a fresh backend pid is what a new connection looks like from outside either of
      // them.
      for
        listener <- PostgresNotificationListener.make(List(channel))
        fiber <- listener.notifications.runForeach {
          case NotificationEvent.Resubscribed => ZIO.never
          case _ => ZIO.unit
        }.fork
        before <- (listenerPid <* ZIO.sleep(50.millis)).repeatUntil(_.nonEmpty).map(_.head)
        _ <- killListener
        after <- (listenerPid.map(_.headOption) <* ZIO.sleep(50.millis))
          .repeatUntil(pid => pid.isDefined && pid != Some(before))
          .timeout(15.seconds)
        _ <- fiber.interrupt
      yield assertTrue(after.flatten.exists(_ != before))
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
