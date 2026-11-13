package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import org.postgresql.PGConnection
import versola.oauth.client.model.TenantId
import versola.util.postgres.{PostgresConfig, PostgresSpec}
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

import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant

/** Binds [[RevocationRepositorySpec]] to auth's `auth_revocations` table. The cases below are what
  * only this table promises: the cause is stored, a write fires the notification the other replicas
  * live on and that notification names the key, expiry and bound a replica needs to apply it without
  * a query, and the feed an edge pulls with its tenant filter.
  */
object PostgresAuthRevocationRepositorySpec extends PostgresSpec, RevocationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresAuthRevocationRepository(xa)
        RevocationRepositorySpec.Env(
          repository = repository,
          revoke = revocation => repository.revoke(revocation, RevocationCause.Revoke, None),
        )

  override def beforeEach(env: RevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit

  override def implementationCases(env: RevocationRepositorySpec.Env) =
    List(
      test("stores why the revocation was written") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- PostgresAuthRevocationRepository(xa).revoke(
            Revocation(RevocationKey.Jti("token-1"), Instant.now().plusSeconds(3600), None),
            RevocationCause.CodeReplay,
            None,
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
          payload <- PostgresAuthRevocationRepository(xa).revoke(written, RevocationCause.UserDeleted, None) *>
            xa.connect(
              sql"""SELECT json_build_object(
                      'key', revoked_key,
                      'exp', extract(epoch from expires_at)::bigint,
                      'before', extract(epoch from issued_before)::bigint)::text
                    FROM auth_revocations WHERE revoked_key = 'sub:user-1'""".query[String].run().head,
            )
        yield assertTrue(PostgresRevocationNotifications.parseNotification(payload).contains(written))
      } @@ TestAspect.withLiveClock,
      // Read off the channel by a session of its own, as a replica does: the trigger's real output, not the same expression rebuilt here.
      // The config is provided around the suite, not inside the test: a test reads the environment through `TestSystem`, which has no
      // `POSTGRES_HOST`, and would open its own session on the default host instead of the one the transactor uses.
      suite("the notification of a write")(
        test("carries the bound truncated to its second, not rounded up to the next one") {
          // A fraction past a half: `::bigint` of the epoch rounds, which put every token minted in the second after the revocation
          // on the revoked side of the bound (`iat` is whole seconds and the boundary second belongs to the revocation).
          val written = Revocation(
            RevocationKey.Sub("user-2"),
            Instant.ofEpochSecond(2_000_000_000L),
            Some(Instant.ofEpochSecond(1_999_999_000L, 700_000_000L)),
          )
          ZIO.scoped:
            for
              config <- ZIO.service[PostgresConfig]
              xa <- ZIO.service[TransactorZIO]
              listener <- ZIO.acquireRelease(
                ZIO.attemptBlocking(DriverManager.getConnection(config.url, config.user, new String(config.password, StandardCharsets.UTF_8))),
              )(connection => ZIO.attemptBlocking(connection.close()).ignore)
              _ <- ZIO.attemptBlocking(listener.createStatement().execute(s"LISTEN ${PostgresAuthRevocationRepository.Table.channel}"))
              _ <- PostgresAuthRevocationRepository(xa).revoke(written, RevocationCause.UserDeleted, None)
              payload <- ZIO.attemptBlocking:
                listener.unwrap(classOf[PGConnection]).getNotifications(5000).map(_.getParameter).find(_.contains("user-2"))
            yield assertTrue(
              payload.flatMap(PostgresRevocationNotifications.parseNotification(_)).flatMap(_.issuedBefore) ==
                Some(Instant.ofEpochSecond(1_999_999_000L)),
            )
        } @@ TestAspect.withLiveClock,
      ).provideSomeLayer[RevocationRepositorySpec.Env & TransactorZIO & Scope](PostgresSpec.config),
      // The feed an edge pulls (GET /revocations): the tenant column exists only in this table, so these are
      // this storage's to promise rather than the trait's.
      test("the feed serves a tenant's rows and the rows of no tenant, and not another tenant's") {
        val expiry = Instant.now().plusSeconds(3600)
        for
          xa <- ZIO.service[TransactorZIO]
          repository = PostgresAuthRevocationRepository(xa)
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Jti("mine"), expiresAt = expiry, issuedBefore = None),
            RevocationCause.Revoke,
            Some(TenantId("tenant-a")),
          )
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Fam("theirs"), expiresAt = expiry, issuedBefore = None),
            RevocationCause.Revoke,
            Some(TenantId("tenant-b")),
          )
          _ <-
            repository.revoke(Revocation(key = RevocationKey.Sid("session"), expiresAt = expiry, issuedBefore = None), RevocationCause.Logout, None)
          page <- repository.feedSince(RevocationCursor.Beginning, 10, Set(TenantId("tenant-a")))
          nobody <- repository.feedSince(RevocationCursor.Beginning, 10, Set.empty)
        yield assertTrue(
          page.revocations.map(_.key).toSet == Set(RevocationKey.Jti("mine"), RevocationKey.Sid("session")),
          nobody.revocations.map(_.key) == List(RevocationKey.Sid("session")),
        )
      } @@ TestAspect.withLiveClock,
      test("the feed resumes after a cursor and reports a full page") {
        val expiry = Instant.now().plusSeconds(3600)
        for
          xa <- ZIO.service[TransactorZIO]
          repository = PostgresAuthRevocationRepository(xa)
          _ <- ZIO.foreachDiscard(1 to 3)(n =>
            repository.revoke(Revocation(key = RevocationKey.Sid(s"s$n"), expiresAt = expiry, issuedBefore = None), RevocationCause.Logout, None),
          )
          first <- repository.feedSince(RevocationCursor.Beginning, 2, Set.empty)
          second <- repository.feedSince(first.last.get, 2, Set.empty)
          third <- repository.feedSince(second.last.get, 2, Set.empty)
        yield assertTrue(
          first.revocations.size == 2,
          first.hasMore,
          second.revocations.size == 1,
          !second.hasMore,
          (first.revocations ++ second.revocations).map(_.key).toSet == Set(1, 2, 3).map(n => RevocationKey.Sid(s"s$n")),
          third.revocations.isEmpty,
          third.last.isEmpty,
        )
      } @@ TestAspect.withLiveClock,
      test("the feed leaves out rows that have expired") {
        for
          xa <- ZIO.service[TransactorZIO]
          repository = PostgresAuthRevocationRepository(xa)
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Sid("old"), expiresAt = Instant.now().minusSeconds(5), issuedBefore = None),
            RevocationCause.Logout,
            None,
          )
          page <- repository.feedSince(RevocationCursor.Beginning, 10, Set.empty)
        yield assertTrue(page.revocations.isEmpty)
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
          revoke = revocation => repository.revoke(revocation, RevocationCause.Revoke, None),
        )

  override def beforeEach(env: RevocationCacheSyncSpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit
