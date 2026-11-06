package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.{PostgresConfig, PostgresSpec}
import zio.*
import zio.test.*

import java.time.Instant

/** What cannot be tested against an abstract `RevocationRepository`: delivery over a real
  * `LISTEN`/`NOTIFY` connection, and recovery from a connection Postgres itself has torn down.
  * Both are specific to how Postgres delivers notifications; the rest of the cache is held to its
  * behaviour in `RevocationCacheSpec`.
  */
object PostgresRevocationCacheSpec extends PostgresSpec:

  private def revocation(id: String) =
    Revocation(RevocationKey.Jti(id), Instant.now().plusSeconds(3600), issuedBefore = None)

  private def clean = ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"DELETE FROM auth_revocations".update.run()))

  private def cache =
    for
      xa <- ZIO.service[TransactorZIO]
      impl <- RevocationCache.make(PostgresRevocationRepository(xa))
    yield impl

  // The listener's connection is found by its name or, failing that, by the statements only it
  // issues: the name is not reported by every driver and server combination.

  /** Ends the listener's session from the server side, which is what a pooler recycling an idle
    * connection or a database restart looks like from the client.
    */
  private def killListener =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT pg_terminate_backend(pid) FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'
                 OR query LIKE 'LISTEN %' OR query LIKE 'NOTIFY versola_listener_heartbeat_%'""".query[Boolean].run(),
      )

  private def listenerConnected =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT count(*) > 0 FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'
                 OR query LIKE 'LISTEN %' OR query LIKE 'NOTIFY versola_listener_heartbeat_%'""".query[Boolean].run().head,
      )

  /** Writes a revocation the way a replica on the other side of a broken feed would: the row lands,
    * the trigger that would have announced it does not fire.
    */
  private def revokeUnannounced(id: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      // Re-enabled on the way out whatever happens: a trigger left disabled by a failing test would
      // quietly turn every test after it into one that cannot see a notification.
      ZIO.acquireReleaseWith(
        xa.connect(sql"ALTER TABLE auth_revocations DISABLE TRIGGER auth_revocations_notify".update.run()),
      )(_ => xa.connect(sql"ALTER TABLE auth_revocations ENABLE TRIGGER auth_revocations_notify".update.run()).orDie)(_ =>
        PostgresRevocationRepository(xa).revoke(revocation(id), RevocationCause.Revoke),
      )

  private def eventually[R](condition: ZIO[R, Throwable, Boolean], label: String) =
    condition.repeatUntil(identity).delay(25.millis)
      .timeoutFail(AssertionError(s"never became true: $label"))(15.seconds)

  private def isRevoked(cache: RevocationCache.Impl, id: String) =
    cache.isRevoked(List(RevocationKey.Jti(id)), Instant.now())

  def spec = (suite("PostgresRevocationCache")(
    test("a revocation written through the repository arrives over the notification feed") {
      (for
        cache <- cache
        notifications <- ZIO.service[RevocationNotifications]
        _ <- cache.consume(notifications).forkScoped
        _ <- eventually(listenerConnected, "listener connected")
        // Written through a second repository on its own connection: this is another replica
        // revoking, or this one, which learns of its own write the same way.
        xa <- ZIO.service[TransactorZIO]
        _ <- PostgresRevocationRepository(xa).revoke(revocation("from-peer"), RevocationCause.Logout)
        _ <- eventually(isRevoked(cache, "from-peer"), "peer revocation observed")
      yield assertCompletes)
        .provideSome[TransactorZIO & PostgresConfig & Scope](PostgresRevocationNotifications.live)
    },
    test("a reconnect recovers a revocation the feed never delivered") {
      (for
        cache <- cache
        notifications <- ZIO.service[RevocationNotifications]
        _ <- cache.consume(notifications).forkScoped
        _ <- eventually(listenerConnected, "listener connected")
        // The feed opens with a resubscribe of its own, so let the sync it triggers finish before
        // writing: otherwise this would be testing whether that one raced the insert.
        _ <- ZIO.sleep(500.millis)
        _ <- cache.sync
        _ <- revokeUnannounced("missed")
        // Nothing announced it and the periodic sync has not come round, so nobody knows.
        unnoticed <- isRevoked(cache, "missed")
        _ <- killListener
        _ <- eventually(isRevoked(cache, "missed"), "missed revocation recovered")
      yield assertTrue(!unnoticed))
        .provideSome[TransactorZIO & PostgresConfig & Scope](PostgresRevocationNotifications.live)
    },
  ) @@ TestAspect.before(clean) @@ TestAspect.after(clean)
    @@ TestAspect.withLiveClock @@ TestAspect.sequential)
    .provideSomeLayer[TransactorZIO & TestEnvironment & Scope](PostgresSpec.config)
