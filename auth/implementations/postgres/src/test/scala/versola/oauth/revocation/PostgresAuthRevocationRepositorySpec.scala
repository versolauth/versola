package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import versola.util.revocation.{
  PostgresRevocationNotifications,
  Revocation,
  RevocationCacheSyncSpec,
  RevocationCursor,
  RevocationKey,
  RevocationRepositorySpec,
}
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Binds [[RevocationRepositorySpec]] to auth's `auth_revocations` table. The cases below are what
  * only this table promises: the cause is stored, a write fires the notification the other replicas
  * live on and that notification names the key, expiry and bound a replica needs to apply it without
  * a query, and the upsert's rule for a key written both with and without a bound.
  */
object PostgresAuthRevocationRepositorySpec extends PostgresSpec, RevocationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresAuthRevocationRepository(xa)
        RevocationRepositorySpec.Env(
          repository = repository,
          revoke = revocation => repository.revoke(revocation, RevocationCause.Revoke),
        )

  override def beforeEach(env: RevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit

  private def sub(id: String) = RevocationKey.Sub(id)

  override def implementationCases(env: RevocationRepositorySpec.Env) =
    List(
      test("stores why the revocation was written") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- PostgresAuthRevocationRepository(xa).revoke(
            Revocation(RevocationKey.Jti("token-1"), Instant.now().plusSeconds(3600), None),
            RevocationCause.CodeReplay,
          )
          cause <- xa.connect(sql"SELECT cause FROM auth_revocations WHERE revoked_key = 'jti:token-1'".query[String].run().head)
        yield assertTrue(cause == "code_replay")
      } @@ TestAspect.withLiveClock,
      test("a notification payload decodes back to the revocation that was written") {
        val written = Revocation(RevocationKey.Sub("user-1"), Instant.ofEpochSecond(2_000_000_000L), Some(Instant.ofEpochSecond(1_999_999_000L)))
        for
          xa <- ZIO.service[TransactorZIO]
          // What the trigger sends, built by Postgres itself from the row: the same expression the
          // trigger uses, so a change to the migration's payload fails here.
          payload <- PostgresAuthRevocationRepository(xa).revoke(written, RevocationCause.UserDeleted) *>
            xa.connect(
              sql"""SELECT json_build_object(
                      'key', revoked_key,
                      'exp', extract(epoch from expires_at)::bigint,
                      'before', extract(epoch from issued_before)::bigint)::text
                    FROM auth_revocations WHERE revoked_key = 'sub:user-1'""".query[String].run().head,
            )
        yield assertTrue(PostgresRevocationNotifications.parseNotification(payload).contains(written))
      } @@ TestAspect.withLiveClock,
      test("re-revoking a bounded key with no bound widens it to cover every token, and never the other way") {
        // The cache's rule: no bound beats any instant. The write has to agree, or the two replicas
        // that read the same row would answer differently for a key written both ways.
        val bound = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val expiry = bound.plusSeconds(3600)
        for
          _ <- env.revoke(Revocation(sub("bounded"), expiry, Some(bound)))
          _ <- env.revoke(Revocation(sub("bounded"), expiry, None))
          _ <- env.revoke(Revocation(sub("unbounded"), expiry, None))
          _ <- env.revoke(Revocation(sub("unbounded"), expiry, Some(bound)))
          page <- env.repository.activeSince(RevocationCursor.Beginning, limit = 10)
          byKey = page.revocations.map(r => r.key -> r.issuedBefore).toMap
        yield assertTrue(byKey(sub("bounded")).isEmpty, byKey(sub("unbounded")).isEmpty)
      } @@ TestAspect.withLiveClock,
    )

/** Binds [[RevocationCacheSyncSpec]] to auth's `auth_revocations` table. */
object PostgresAuthRevocationCacheSyncSpec extends PostgresSpec, RevocationCacheSyncSpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresAuthRevocationRepository(xa)
        RevocationCacheSyncSpec.Env(
          repository = repository,
          revoke = revocation => repository.revoke(revocation, RevocationCause.Revoke),
        )

  override def beforeEach(env: RevocationCacheSyncSpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit
