package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PostgresAntifraudProposalRepositorySpec extends PostgresSpec, AntifraudProposalRepositorySpec:

  import AntifraudProposalRepositorySpec.*

  private case class ProposalRow(
      tenantId: String,
      kind: String,
      valueType: String,
      value: String,
      suggestedAction: String,
      ttlSeconds: Long,
      status: String,
      evidence: String,
  ) derives DbCodec

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def stats(
        tenant: String,
        prefix: String,
        from: Instant,
        to: Instant,
        requests: Int,
        sent: Int,
        verified: Int,
        fakeVerifies: Int,
    ): Task[Unit] =
      xa.connect:
        sql"""
          INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled, provider_errors,
                                     verified, wrong_codes, fake_verifies)
          SELECT $tenant, $prefix, m, $requests, $sent, 0, 0, 0, $verified, 0, $fakeVerifies
          FROM generate_series($from::timestamptz, ($to::timestamptz - interval '1 minute'), interval '1 minute') AS m
          ON CONFLICT (tenant_id, dest_prefix, minute) DO UPDATE SET requests = EXCLUDED.requests, sent = EXCLUDED.sent,
            verified = EXCLUDED.verified, fake_verifies = EXCLUDED.fake_verifies
        """.update.run()
      .unit

    override def proposals: Task[List[Proposal]] =
      xa.connect:
        sql"""SELECT tenant_id, kind, value_type, value, suggested_action, ttl_seconds, status, evidence::text
              FROM af_proposal ORDER BY tenant_id, kind, value, created_at""".query[ProposalRow].run().toList
      .map(_.map(r =>
        Proposal(
          tenantId = r.tenantId,
          kind = r.kind,
          valueType = r.valueType,
          value = r.value,
          suggestedAction = r.suggestedAction,
          ttlSeconds = r.ttlSeconds,
          status = r.status,
          evidence = r.evidence,
        ),
      ))

    override def propose(tenant: String, kind: String, value: String, status: String, createdAt: Instant, decidedAt: Option[Instant]): Task[Unit] =
      xa.connect:
        sql"""
          INSERT INTO af_proposal (id, tenant_id, kind, value_type, value, evidence, suggested_action, ttl_seconds, status,
                                   created_at, decided_by, decided_at)
          VALUES (${UUID.randomUUID()}, $tenant, $kind, 'prefix', $value, '{}'::jsonb, 'deny', 3600, $status, $createdAt,
                  ${decidedAt.map(_ => "someone")}, $decidedAt)
        """.update.run()
      .unit

    override def listed(value: String, tenant: Option[String], expiresAt: Option[Instant], listType: String): Task[Unit] =
      xa.connect:
        sql"""
          INSERT INTO af_list_entry (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at,
                                     hit_count, last_hit_at)
          VALUES (${UUID.randomUUID()}, $listType, 'prefix', $value, $tenant, $expiresAt, NULL, 'manual', FALSE, 'op', $now, 0, NULL)
        """.update.run()
      .unit

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresAntifraudProposalRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"DELETE FROM af_dest_stats".update.run()
        sql"DELETE FROM af_proposal".update.run()
        sql"DELETE FROM af_list_entry".update.run()
    yield ()

  override def implementationCases(env: Env) =
    List(
      test("gives way to an instance that is running it") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- env.fixtures.stats("tenant-a", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          holder <- xa.transact:
            sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock(7430004)) l".query[Int].run()
            Thread.sleep(1500)
          .fork
          _ <- Live.live(ZIO.sleep(400.millis))
          skipped <- env.repository.detectPrefixAnomalies(now, anomaly, limits)
          _ <- holder.join
          after <- env.repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(skipped.isEmpty, after == Some(1))
      },
    )
