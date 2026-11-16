package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import org.postgresql.PGConnection
import versola.util.postgres.{PostgresConfig, PostgresSpec}
import versola.util.revocation.{PostgresRevocationNotifications, Revocation, RevocationCacheSyncSpec, RevocationKey, RevocationRepositorySpec}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant

/** Binds [[RevocationRepositorySpec]] to auth's `auth_revocations` table. The cases below are what
  * only this table promises: a write fires the notification the other replicas live on and that
  * notification names the key, expiry and bound a replica needs to apply it without a query. What
  * auth's own trait adds (the stored cause, the feed an edge pulls with its tenant filter) is
  * [[AuthRevocationRepositorySpec]], bound below.
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

/** Binds [[AuthRevocationRepositorySpec]] to auth's `auth_revocations` table. */
object PostgresAuthRevocationFeedSpec extends PostgresSpec, AuthRevocationRepositorySpec:

  private class PostgresFixtures(xa: TransactorZIO) extends AuthRevocationRepositorySpec.Fixtures:
    override def storedCause(key: RevocationKey): Task[Option[String]] =
      xa.connect(sql"SELECT cause FROM auth_revocations WHERE revoked_key = ${key.encoded}".query[String].run().headOption)

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AuthRevocationRepositorySpec.Env(PostgresAuthRevocationRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: AuthRevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit
