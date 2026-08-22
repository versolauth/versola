package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.test.*

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
