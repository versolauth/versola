package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.{PostgresConfig, PostgresSpec}
import zio.*
import zio.test.*

import java.time.Instant

/** What cannot be tested against an abstract `RevocationRepository`: delivery over a real
  * `LISTEN`/`NOTIFY` connection, from the trigger of `V0032` to the cache. Recovery from a dropped
  * connection is the listener's own (`PostgresNotificationListenerSpec`) and what the cache does on
  * a resubscribe is `RevocationCacheSpec`'s; the rest of the cache is held to its behaviour there.
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

  private def listenerConnected =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT count(*) > 0 FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'""".query[Boolean].run().head,
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
  ) @@ TestAspect.before(clean) @@ TestAspect.after(clean)
    @@ TestAspect.withLiveClock @@ TestAspect.sequential)
    .provideSomeLayer[TransactorZIO & TestEnvironment & Scope](PostgresSpec.config)
