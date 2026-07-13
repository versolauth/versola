package versola.cleanup

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
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

  private def makeManager(xa: TransactorZIO): UIO[TestableCleanupManager] =
    Ref.make(List.empty[Fiber.Runtime[Throwable, Long]]).map(TestableCleanupManager(xa, _))

  override val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] =
    suite("PostgresCleanupManagerSpec")(
      test("deletes expired rows and keeps active ones (keyColumn=id, auth_conversations)") {
        for
          xa      <- ZIO.service[TransactorZIO]
          _       <- xa.connect(sql"TRUNCATE TABLE auth_conversations".update.run())
          manager <- makeManager(xa)
          id1      = java.util.UUID.randomUUID()
          id2      = java.util.UUID.randomUUID()
          _ <- xa.connect:
            sql"""
              INSERT INTO auth_conversations
                (id, client_id, redirect_uri, scope, code_challenge, code_challenge_method,
                 step, response_type, auth_flow, version, amr, needs_password_change, expires_at)
              VALUES
                ($id1, 'c1', 'https://x.com', ARRAY['openid'], 'ch', 'S256',
                 '{"type":"start"}'::json, 'code', '{"type":"pwd"}'::jsonb, 1, '[]'::jsonb,
                 false, NOW() - INTERVAL '2 minutes'),
                ($id2, 'c1', 'https://x.com', ARRAY['openid'], 'ch', 'S256',
                 '{"type":"start"}'::json, 'code', '{"type":"pwd"}'::jsonb, 1, '[]'::jsonb,
                 false, NOW() + INTERVAL '5 minutes')
            """.update.run()
          _             <- manager.runBatch("auth_conversations", 1000, "id")
          expiredExists <- xa.connect(sql"SELECT COUNT(*) FROM auth_conversations WHERE id = $id1".query[Long].run().head)
          activeExists  <- xa.connect(sql"SELECT COUNT(*) FROM auth_conversations WHERE id = $id2".query[Long].run().head)
        yield assertTrue(expiredExists == 0L, activeExists == 1L)
      },
      test("deletes expired authorization_codes (keyColumn=code)") {
        for
          xa      <- ZIO.service[TransactorZIO]
          _       <- xa.connect(sql"TRUNCATE TABLE authorization_codes".update.run())
          manager <- makeManager(xa)
          userId1  = java.util.UUID.randomUUID()
          userId2  = java.util.UUID.randomUUID()
          _ <- xa.connect:
            sql"""
              INSERT INTO authorization_codes
                (code, client_id, user_id, session_id, redirect_uri, scope,
                 code_challenge, code_challenge_method, expires_at,
                 used, access_token, amr, auth_time)
              VALUES
                (decode('0101', 'hex'), 'c1', $userId1, decode('02', 'hex'), 'https://x.com', ARRAY['openid'],
                 'ch', 'S256', NOW() - INTERVAL '1 minute',
                 false, decode('03', 'hex'), '[]'::jsonb, NOW()),
                (decode('0202', 'hex'), 'c1', $userId2, decode('02', 'hex'), 'https://x.com', ARRAY['openid'],
                 'ch', 'S256', NOW() + INTERVAL '5 minutes',
                 false, decode('04', 'hex'), '[]'::jsonb, NOW())
            """.update.run()
          _             <- manager.runBatch("authorization_codes", 1000, "code")
          expiredExists <- xa.connect(sql"SELECT COUNT(*) FROM authorization_codes WHERE code = decode('0101', 'hex')".query[Long].run().head)
          activeExists  <- xa.connect(sql"SELECT COUNT(*) FROM authorization_codes WHERE code = decode('0202', 'hex')".query[Long].run().head)
        yield assertTrue(expiredExists == 0L, activeExists == 1L)
      },
      test("deletes expired challenge_throttle rows (keyColumn=ctid, composite PK)") {
        for
          xa      <- ZIO.service[TransactorZIO]
          _       <- xa.connect(sql"TRUNCATE TABLE challenge_throttle".update.run())
          manager <- makeManager(xa)
          _ <- xa.connect:
            sql"""
              INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, expires_at)
              VALUES
                ('u1', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '1 minute'),
                ('u2', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '2 minutes'),
                ('u3', 't1', 'otp', '[]'::jsonb, NOW() + INTERVAL '5 minutes')
            """.update.run()
          deleted   <- manager.runBatch("challenge_throttle", 1000, "ctid")
          remaining <- xa.connect(sql"SELECT COUNT(*) FROM challenge_throttle".query[Long].run().head)
        yield assertTrue(deleted == 2, remaining == 1L)
      },
      test("respects batch size limit") {
        for
          xa      <- ZIO.service[TransactorZIO]
          _       <- xa.connect(sql"TRUNCATE TABLE challenge_throttle".update.run())
          manager <- makeManager(xa)
          _ <- xa.connect:
            sql"""
              INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, expires_at)
              VALUES
                ('u1', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '3 minutes'),
                ('u2', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '2 minutes'),
                ('u3', 't1', 'otp', '[]'::jsonb, NOW() - INTERVAL '1 minute')
            """.update.run()
          deleted   <- manager.runBatch("challenge_throttle", 2, "ctid")
          remaining <- xa.connect(sql"SELECT COUNT(*) FROM challenge_throttle".query[Long].run().head)
        yield assertTrue(deleted == 2, remaining == 1L)
      },
    ) @@ TestAspect.sequential
