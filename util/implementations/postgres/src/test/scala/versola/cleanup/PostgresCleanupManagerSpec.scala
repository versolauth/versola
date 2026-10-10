package versola.cleanup

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{SqlLiteral, sql}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

object PostgresCleanupManagerSpec extends PostgresSpec:

  /** Exposes the protected cleanupBatch method for testing. */
  private class TestableCleanupManager(
      xa: TransactorZIO,
      fibers: Ref[List[Fiber.Runtime[Throwable, Long]]],
  ) extends PostgresCleanupManager(xa, CleanupConfig(maxThreads = 1, tables = List.empty), fibers):
    def runBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int] =
      cleanupBatch(tableName, batchSize, keyColumn)
    def expiring: Task[List[CleanupManager.ExpiryTable]] = tablesWithExpiry
    def expired(tableName: String, cap: Int): Task[Option[CleanupManager.ExpiredStats]] = expiredStats(tableName, cap)
    def estimate(tableName: String): Task[Option[Long]] = estimatedRows(tableName)

  private def makeManager(xa: TransactorZIO): UIO[TestableCleanupManager] =
    Ref.make(List.empty[Fiber.Runtime[Throwable, Long]]).map(TestableCleanupManager(xa, _))

  private def withChallengeThrottleTable[R, A](xa: TransactorZIO)(
      run: (String, SqlLiteral) => ZIO[R, Throwable, A],
  ): ZIO[R, Throwable, A] =
    val tableName = s"cleanup_ct_${java.util.UUID.randomUUID().toString.replace("-", "")}"
    val table = SqlLiteral(tableName)
    val create = xa.connect:
      sql"""
        CREATE TABLE $table (
          subject TEXT NOT NULL,
          tenant_id TEXT NOT NULL,
          challenge_type TEXT NOT NULL,
          attempts JSONB NOT NULL,
          expires_at TIMESTAMPTZ NOT NULL,
          PRIMARY KEY (subject, tenant_id, challenge_type)
        )
      """.update.run()
    create.unit *> run(tableName, table)
      .ensuring(xa.connect(sql"DROP TABLE IF EXISTS $table".update.run()).ignore)

  /** Two throwaway tables with an `expires_at`: one indexed on it, one not. */
  private def withExpiringTables[R, A](xa: TransactorZIO)(run: (String, String) => ZIO[R, Throwable, A]): ZIO[R, Throwable, A] =
    val suffix = java.util.UUID.randomUUID().toString.replace("-", "")
    val indexed = s"stats_idx_$suffix"
    val plain = s"stats_plain_$suffix"
    val create = xa.connect:
      sql"CREATE TABLE ${SqlLiteral(indexed)} (id INT PRIMARY KEY, expires_at TIMESTAMPTZ NOT NULL)".update.run()
      sql"CREATE INDEX ${SqlLiteral(indexed + "_exp")} ON ${SqlLiteral(indexed)} (expires_at)".update.run()
      sql"CREATE TABLE ${SqlLiteral(plain)} (id INT PRIMARY KEY, expires_at TIMESTAMPTZ NOT NULL)".update.run()
    create.unit *> run(indexed, plain).ensuring(
      xa.connect:
        sql"DROP TABLE IF EXISTS ${SqlLiteral(indexed)}".update.run()
        sql"DROP TABLE IF EXISTS ${SqlLiteral(plain)}".update.run()
      .ignore,
    )

  override val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] =
    suite("PostgresCleanupManagerSpec")(
      test("lists the tables that have an expires_at and says which have an index leading with it") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          found <- withExpiringTables(xa): (indexed, plain) =>
            manager.expiring.map(all => (all.find(_.name == indexed), all.find(_.name == plain)))
        yield assertTrue(
          found._1.exists(_.indexed),
          found._2.exists(!_.indexed),
        )
      },
      test("counts expired rows up to the cap and reports how long ago the oldest expired") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          result <- withExpiringTables(xa): (indexed, _) =>
            val table = SqlLiteral(indexed)
            for
              _ <- xa.connect:
                sql"""
                  INSERT INTO $table (id, expires_at) VALUES
                    (1, NOW() - INTERVAL '10 minutes'), (2, NOW() - INTERVAL '5 minutes'),
                    (3, NOW() - INTERVAL '1 minute'), (4, NOW() + INTERVAL '1 hour')
                """.update.run()
              all <- manager.expired(indexed, 1000)
              capped <- manager.expired(indexed, 2)
            yield (all, capped)
        yield assertTrue(
          result._1.exists(_.rows == 3L),
          result._1.exists(s => s.oldestAgeSeconds > 590 && s.oldestAgeSeconds < 650),
          result._2.exists(_.rows == 2L),
        )
      },
      test("reports no expired rows and age 0 for a table nothing has expired in") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          stats <- withExpiringTables(xa): (indexed, _) =>
            xa.connect(sql"INSERT INTO ${SqlLiteral(indexed)} (id, expires_at) VALUES (1, NOW() + INTERVAL '1 hour')".update.run()) *>
              manager.expired(indexed, 1000)
        yield assertTrue(stats.contains(CleanupManager.ExpiredStats(0L, 0.0)))
      },
      test("estimates rows only once the table has been analysed, and has none for a table that does not exist") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          result <- withExpiringTables(xa): (indexed, _) =>
            for
              fresh <- manager.estimate(indexed)
              _ <- xa.connect:
                sql"INSERT INTO ${SqlLiteral(indexed)} (id, expires_at) SELECT g, NOW() FROM generate_series(1, 500) g".update.run()
                sql"ANALYZE ${SqlLiteral(indexed)}".update.run()
              analysed <- manager.estimate(indexed)
            yield (fresh, analysed)
          missing <- manager.estimate("no_such_table_" + java.util.UUID.randomUUID().toString.replace("-", ""))
        yield assertTrue(result._2.contains(500L), missing.isEmpty)
      },
      test("deletes expired rows and keeps active ones (keyColumn=id, auth_conversations)") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations".update.run())
          manager <- makeManager(xa)
          id1 = java.util.UUID.randomUUID()
          id2 = java.util.UUID.randomUUID()
          _ <- xa.connect:
            sql"""
              INSERT INTO auth_conversations
                (id, client_id, redirect_uri, scope, code_challenge, code_challenge_method,
                resources, step, response_type, response_mode, auth_flow, version, amr, needs_password_change, csrf_token, expires_at, prompt_consent)
              VALUES
                ($id1, 'c1', 'https://x.com', ARRAY['openid'], 'ch', 'S256', ARRAY[]::text[],
                '{"type":"start"}'::json, 'code', 'query', '{"type":"pwd"}'::jsonb, 1, '[]'::jsonb,
                false, '', NOW() - INTERVAL '2 minutes', false),
                ($id2, 'c1', 'https://x.com', ARRAY['openid'], 'ch', 'S256', ARRAY[]::text[],
                '{"type":"start"}'::json, 'code', 'query', '{"type":"pwd"}'::jsonb, 1, '[]'::jsonb,
                false, '', NOW() + INTERVAL '5 minutes', false)
            """.update.run()
          _ <- manager.runBatch("auth_conversations", 1000, "id")
          expiredExists <- xa.connect(sql"SELECT COUNT(*) FROM auth_conversations WHERE id = $id1".query[Long].run().head)
          activeExists <- xa.connect(sql"SELECT COUNT(*) FROM auth_conversations WHERE id = $id2".query[Long].run().head)
        yield assertTrue(expiredExists == 0L, activeExists == 1L)
      },
      test("deletes expired authorization_codes (keyColumn=code)") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE authorization_codes".update.run())
          manager <- makeManager(xa)
          userId1 = java.util.UUID.randomUUID()
          userId2 = java.util.UUID.randomUUID()
          _ <- xa.connect:
            sql"""
              INSERT INTO authorization_codes
                (code, client_id, user_id, session_id, public_session_id, redirect_uri, scope,
                 resources, code_challenge, code_challenge_method, expires_at,
                 used, access_token, amr, auth_time, family_id)
              VALUES
                (decode('0101', 'hex'), 'c1', $userId1, decode('02', 'hex'), 'sid1', 'https://x.com', ARRAY['openid'],
                 ARRAY[]::text[], 'ch', 'S256', NOW() - INTERVAL '1 minute',
                 false, decode('03', 'hex'), '[]'::jsonb, NOW(), 'fam1'),
                (decode('0202', 'hex'), 'c1', $userId2, decode('02', 'hex'), 'sid2', 'https://x.com', ARRAY['openid'],
                 ARRAY[]::text[], 'ch', 'S256', NOW() + INTERVAL '5 minutes',
                 false, decode('04', 'hex'), '[]'::jsonb, NOW(), 'fam2')
            """.update.run()
          _ <- manager.runBatch("authorization_codes", 1000, "code")
          expiredExists <- xa.connect(sql"SELECT COUNT(*) FROM authorization_codes WHERE code = decode('0101', 'hex')".query[Long].run().head)
          activeExists <- xa.connect(sql"SELECT COUNT(*) FROM authorization_codes WHERE code = decode('0202', 'hex')".query[Long].run().head)
        yield assertTrue(expiredExists == 0L, activeExists == 1L)
      },
      test("deletes expired challenge_throttle rows (keyColumn=ctid, composite PK)") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          result <- withChallengeThrottleTable(xa): (tableName, table) =>
            for
              _ <- xa.connect:
                sql"""
                  INSERT INTO $table (subject, tenant_id, challenge_type, attempts, expires_at)
                  VALUES
                    ('u1', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '1 minute'),
                    ('u2', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '2 minutes'),
                    ('u3', 't1', 'otp', '[]'::jsonb, NOW() + INTERVAL '5 minutes')
                """.update.run()
              deleted <- manager.runBatch(tableName, 1000, "ctid")
              remaining <- xa.connect(sql"SELECT COUNT(*) FROM $table".query[Long].run().head)
            yield assertTrue(deleted == 2, remaining == 1L)
        yield result
      },
      test("respects batch size limit") {
        for
          xa <- ZIO.service[TransactorZIO]
          manager <- makeManager(xa)
          result <- withChallengeThrottleTable(xa): (tableName, table) =>
            for
              _ <- xa.connect:
                sql"""
                  INSERT INTO $table (subject, tenant_id, challenge_type, attempts, expires_at)
                  VALUES
                    ('u1', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '3 minutes'),
                    ('u2', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '2 minutes'),
                    ('u3', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '1 minute')
                """.update.run()
              deleted <- manager.runBatch(tableName, 2, "ctid")
              remaining <- xa.connect(sql"SELECT COUNT(*) FROM $table".query[Long].run().head)
            yield assertTrue(deleted == 2, remaining == 1L)
        yield result
      },
    ) @@ TestAspect.sequential
