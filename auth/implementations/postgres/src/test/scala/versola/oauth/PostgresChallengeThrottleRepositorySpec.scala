package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.client.model.{RateLimit, TenantId}
import versola.oauth.conversation.limit.{
  ChallengeThrottleRecord,
  ChallengeThrottleRepositorySpec,
  ChallengeType,
  LimitStatus,
  PostgresChallengeThrottleRepository,
  Reservation,
}
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{NonEmptyChunk, ZIO, ZLayer, durationInt}

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}

object PostgresChallengeThrottleRepositorySpec extends PostgresSpec, ChallengeThrottleRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield ChallengeThrottleRepositorySpec.Env(PostgresChallengeThrottleRepository(xa))

  override def beforeEach(env: ChallengeThrottleRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE challenge_throttle".update.run())
    yield ()

  // Only this storage takes a row lock, so what is promised about it is promised here: a denial that is already
  // plain from the row is answered without queueing on it.
  override def implementationCases(env: ChallengeThrottleRepositorySpec.Env) =
    val tenantId = TenantId("lock-tenant")
    val subject = "lock-subject"
    val now = Instant.parse("2030-06-01T00:00:00Z")
    val limits = NonEmptyChunk(RateLimit(maxAttempts = 3, windowSeconds = 60), RateLimit(maxAttempts = 1000, windowSeconds = 3600))
    val full = (1 to 3).map(i => now.minusSeconds(i.toLong).getEpochSecond).toList
    def holdingRowLock[A](effect: ZIO[TransactorZIO, Throwable, A]) =
      val locked = CountDownLatch(1)
      val release = CountDownLatch(1)
      ZIO.serviceWithZIO[TransactorZIO]: xa =>
        for
          holder <- xa.transact:
            sql"""SELECT 1 FROM challenge_throttle WHERE tenant_id = $tenantId AND subject = $subject FOR UPDATE""".query[Int].run()
            locked.countDown()
            release.await(30, TimeUnit.SECONDS)
          .fork
          _ <- ZIO.attemptBlocking(locked.await(5, TimeUnit.SECONDS))
          result <- effect.disconnect.timeout(5.seconds).ensuring(ZIO.succeed(release.countDown()))
          _ <- holder.join
        yield result
    def reserved = ZIO.serviceWithZIO[TransactorZIO]: xa =>
      PostgresChallengeThrottleRepository(xa)
        .reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, limits, banDurationSeconds = 900L)
    List(
      test("reserveAttempt denies a subject whose hard window is full without waiting for the row's lock") {
        val record = ChallengeThrottleRecord(tenantId, subject, ChallengeType.PasswordSubmit, full, None, now.plusSeconds(3600))
        for
          _ <- env.repository.upsert(record)
          answered <- holdingRowLock(reserved)
        yield assertTrue(
          answered.exists:
            case Reservation.Denied(LimitStatus.RateLimited(_)) => true
            case _ => false,
        )
      } @@ TestAspect.withLiveClock,
      test("reserveAttempt still decides a subject that is under its limits under the row's lock") {
        val record = ChallengeThrottleRecord(tenantId, subject, ChallengeType.PasswordSubmit, List(now.getEpochSecond), None, now.plusSeconds(3600))
        for
          _ <- env.repository.upsert(record)
          answered <- holdingRowLock(reserved)
        yield assertTrue(answered.isEmpty)
      } @@ TestAspect.withLiveClock,
    )
