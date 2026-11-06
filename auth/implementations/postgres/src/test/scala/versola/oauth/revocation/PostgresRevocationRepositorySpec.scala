package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** Binds [[RevocationRepositorySpec]] to Postgres. The cases below are what only Postgres
  * promises: the cause is stored, a write fires the notification the other replicas live on, and
  * a notification names the key, expiry and bound a replica needs to apply it without a query.
  */
object PostgresRevocationRepositorySpec extends PostgresSpec, RevocationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield RevocationRepositorySpec.Env(PostgresRevocationRepository(xa))

  override def beforeEach(env: RevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE auth_revocations".update.run())
    }.unit

  override def implementationCases(env: RevocationRepositorySpec.Env) =
    List(
      test("stores why the revocation was written") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- env.repository.revoke(
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
          payload <- env.repository.revoke(written, RevocationCause.UserDeleted) *>
            xa.connect(
              sql"""SELECT json_build_object(
                      'key', revoked_key,
                      'exp', extract(epoch from expires_at)::bigint,
                      'before', extract(epoch from issued_before)::bigint)::text
                    FROM auth_revocations WHERE revoked_key = 'sub:user-1'""".query[String].run().head,
            )
        yield assertTrue(PostgresRevocationNotifications.parseNotification(payload).contains(written))
      } @@ TestAspect.withLiveClock,
      test("a payload this version cannot read is dropped rather than failing the stream") {
        assertTrue(
          PostgresRevocationNotifications.parseNotification("not json").isEmpty,
          PostgresRevocationNotifications.parseNotification("""{"key":"zzz:1","exp":1,"before":null}""").isEmpty,
        )
      },
    )
