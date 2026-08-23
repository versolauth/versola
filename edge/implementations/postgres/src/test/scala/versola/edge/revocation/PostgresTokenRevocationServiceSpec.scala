package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.model.{AccessTokenId, AuthorizationPreset, PresetId}
import versola.edge.{OAuthClientService, PostgresRevocationNotifications, PostgresRevocationRepository}
import versola.util.ReloadingCache
import versola.util.postgres.{PostgresConfig, PostgresSpec}
import zio.*
import zio.test.*

import java.time.Instant

/** Joins the two halves that are otherwise only tested apart: the notification feed against a
  * real database, and the cursor sync against a real table. What lives in the seam is the
  * behaviour on a gap in the feed — a revocation nobody delivered has to be found by reading,
  * and reading now starts from a cursor rather than at the beginning.
  */
object PostgresTokenRevocationServiceSpec extends ZIOSpecDefault:

  /** No clients configured: nothing here goes through the TTL-derived paths. */
  private val clientService: OAuthClientService =
    OAuthClientService.Impl(
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset]))),
      ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[versola.edge.model.ClientId, versola.edge.model.OAuthClient]))),
    )

  private def service =
    for
      xa <- ZIO.service[TransactorZIO]
      impl <- TokenRevocationService.make(PostgresRevocationRepository(xa), clientService)
    yield impl

  private def revocation(id: String) =
    Revocation(RevocationKey.Jti(AccessTokenId(id)), Instant.now().plusSeconds(3600), issuedBefore = None)

  private def clean = ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"DELETE FROM revocations".update.run()))

  /** Writes a revocation the way a replica on the other side of a broken feed would: the row
    * lands, the trigger that would have announced it does not fire. This is the notification
    * that gets lost, made to happen on purpose.
    */
  private def revokeUnannounced(id: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      // Re-enabled on the way out whatever happens: a trigger left disabled by a failing test
      // would quietly turn every test after it into one that cannot see a notification.
      ZIO.acquireReleaseWith(
        xa.connect(sql"ALTER TABLE revocations DISABLE TRIGGER revocations_notify".update.run()),
      )(_ => xa.connect(sql"ALTER TABLE revocations ENABLE TRIGGER revocations_notify".update.run()).orDie)(_ =>
        PostgresRevocationRepository(xa).revokeAll(List(revocation(id))),
      )

  /** Ends the listener's session from the server side, which is what a pooler recycling an
    * idle connection or a database restart looks like from the client.
    */
  private def killListener =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT pg_terminate_backend(pid) FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'""".query[Boolean].run(),
      )

  private def listenerConnected =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(
        sql"""SELECT count(*) > 0 FROM pg_stat_activity
              WHERE application_name = 'versola-notification-listener'""".query[Boolean].run().head,
      )

  private def eventually[R](condition: ZIO[R, Throwable, Boolean], label: String) =
    condition.repeatUntil(identity).delay(25.millis)
      .timeoutFail(AssertionError(s"never became true: $label"))(15.seconds)

  private def isRevoked(service: TokenRevocationService.Impl, id: String) =
    service.isRevoked(List(RevocationKey.Jti(AccessTokenId(id))), Instant.now())

  def spec = (suite("PostgresTokenRevocationService")(
    test("a revocation written by another replica arrives over the notification feed") {
      (for
        service <- service
        notifications <- ZIO.service[RevocationNotifications]
        _ <- service.consume(notifications).forkScoped
        _ <- eventually(listenerConnected, "listener connected")
        // Written through a second repository on its own connection: this is another replica
        // revoking, not this service revoking through itself.
        xa <- ZIO.service[TransactorZIO]
        _ <- PostgresRevocationRepository(xa).revokeAll(List(revocation("from-peer")))
        _ <- eventually(isRevoked(service, "from-peer"), "peer revocation observed")
      yield assertCompletes)
        .provideSome[TransactorZIO & PostgresConfig & Scope](PostgresRevocationNotifications.live)
    },
    test("a reconnect recovers a revocation the feed never delivered") {
      (for
        service <- service
        notifications <- ZIO.service[RevocationNotifications]
        _ <- service.consume(notifications).forkScoped
        _ <- eventually(listenerConnected, "listener connected")
        // The feed opens with a resubscribe of its own, so let the sync it triggers finish
        // before writing: otherwise this would be testing whether that one raced the insert.
        _ <- ZIO.sleep(500.millis)
        _ <- service.sync
        _ <- revokeUnannounced("missed")
        // Nothing announced it and the periodic sync has not come round, so the only replica
        // that knows is the one that wrote it.
        unnoticed <- isRevoked(service, "missed")
        _ <- killListener
        _ <- eventually(isRevoked(service, "missed"), "missed revocation recovered")
      yield assertTrue(!unnoticed))
        .provideSome[TransactorZIO & PostgresConfig & Scope](PostgresRevocationNotifications.live)
    },
    test("a later sync picks up what was written after the last one without re-reading it") {
      for
        service <- service
        _ <- revokeUnannounced("first")
        _ <- service.sync
        afterFirst <- service.entryCount
        _ <- revokeUnannounced("second")
        _ <- service.sync
        afterSecond <- service.entryCount
        first <- isRevoked(service, "first")
        second <- isRevoked(service, "second")
      yield assertTrue(afterFirst == 1, afterSecond == 2, first, second)
    },
  ) @@ TestAspect.before(clean) @@ TestAspect.after(clean)
    @@ TestAspect.withLiveClock @@ TestAspect.sequential)
    .provideSome[Scope](PostgresSpec.transactor, PostgresSpec.config)
