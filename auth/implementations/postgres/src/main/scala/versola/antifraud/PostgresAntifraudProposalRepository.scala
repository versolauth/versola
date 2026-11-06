package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.antifraud.AntifraudDetectors.{FakeVerify, PrefixAnomaly, Run}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

import java.time.Instant
import java.time.temporal.ChronoUnit

class PostgresAntifraudProposalRepository(xa: TransactorZIO) extends AntifraudProposalRepository, BasicCodecs:
  import PostgresAntifraudProposalRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  /** Takes the lock of the detector for the length of the transaction, and gives the statements their timeout; both
    * end with it, so neither stays on a pooled connection. `false` when another instance holds the lock. */
  private def guarded(lock: Long, statementTimeout: Duration)(using DbCon): Boolean =
    val timeout = SqlLiteral(s"'${statementTimeout.toMillis}ms'")
    sql"SET LOCAL statement_timeout = $timeout".update.run()
    sql"SELECT pg_try_advisory_xact_lock($lock)".query[Boolean].run().head

  /** A value that is open, or was decided on lately, or is covered by an entry (of any list, shadow ones too: an
    * operator who is trying a value out, or has allowed it, is not to be asked about it again) is not proposed.
    * Reads `c.tenant_id` and `c.value`. */
  private def notProposedOrListed(kind: ProposalKind, valueType: AntifraudValueType, now: Instant, cooldown: Duration): Frag =
    val decidedSince = now.minusSeconds(cooldown.getSeconds)
    val k = kind.toString
    val vt = valueType.toString
    sql"""
      NOT EXISTS (
        SELECT 1 FROM af_proposal p
        WHERE p.tenant_id = c.tenant_id AND p.kind = $k AND p.value = c.value
          AND (p.status = 'open' OR (p.status IN ('approved', 'rejected') AND p.decided_at >= $decidedSince))
      )
      AND NOT EXISTS (
        SELECT 1 FROM af_list_entry e
        WHERE e.value_type = $vt AND starts_with(c.value, e.value)
          AND (e.tenant_id = c.tenant_id OR e.tenant_id IS NULL)
          AND (e.expires_at IS NULL OR e.expires_at > $now)
      )
    """

  override def detectPrefixAnomalies(now: Instant, s: PrefixAnomaly, run: Run): Task[Option[Int]] =
    xa.transactMeasured("detect-antifraud-prefix-anomalies"):
      if !guarded(PrefixAnomalyLock, run.statementTimeout) then None
      else
        val to = windowEnd(now, run.settle)
        val from = to.minusSeconds(s.window.getSeconds)
        val baseFrom = from.minusSeconds(s.baseline.getSeconds)
        val windowSeconds = s.window.getSeconds
        val baselineSeconds = s.baseline.getSeconds
        val ttl = run.ttl.getSeconds
        val unlisted = notProposedOrListed(ProposalKind.prefix_anomaly, AntifraudValueType.prefix, now, run.cooldown)
        // The busiest ranges of the window first, and only those are looked at closely: the baseline of each is one
        // range scan of the primary key, so the cost follows the candidates and not the length of the baseline.
        Some(sql"""
          WITH recent AS (
            SELECT tenant_id, dest_prefix AS value, SUM(requests) AS requests, SUM(sent) AS sent, SUM(verified) AS verified
            FROM af_dest_stats
            WHERE minute >= $from AND minute < $to AND tenant_id <> ''
            GROUP BY tenant_id, dest_prefix
            HAVING SUM(requests) >= ${s.minRequests}
            ORDER BY SUM(requests) DESC
            LIMIT ${run.candidates}
          ), c AS (
            SELECT r.tenant_id, r.value, r.requests, r.sent, r.verified,
                   b.sent AS b_sent, b.verified AS b_verified,
                   GREATEST(b.requests::float8 * $windowSeconds::float8 / $baselineSeconds::float8, ${s.baselineFloor}::float8) AS expected
            FROM recent r
            CROSS JOIN LATERAL (
              SELECT COALESCE(SUM(requests), 0) AS requests, COALESCE(SUM(sent), 0) AS sent, COALESCE(SUM(verified), 0) AS verified
              FROM af_dest_stats b
              WHERE b.tenant_id = r.tenant_id AND b.dest_prefix = r.value AND b.minute >= $baseFrom AND b.minute < $from
            ) b
          )
          INSERT INTO af_proposal (id, tenant_id, kind, value_type, value, evidence, suggested_action, ttl_seconds, status,
                                   created_at, decided_by, decided_at)
          SELECT gen_random_uuid(), c.tenant_id, 'prefix_anomaly', 'prefix', c.value,
                 jsonb_build_object(
                   'requests', c.requests, 'sent', c.sent, 'verified', c.verified,
                   'expected_requests', round(c.expected::numeric, 1),
                   'conversion', round(c.verified::numeric / c.sent, 3),
                   'baseline_sent', c.b_sent,
                   'baseline_conversion', CASE WHEN c.b_sent > 0 THEN round(c.b_verified::numeric / c.b_sent, 3) END,
                   'window_seconds', $windowSeconds, 'baseline_seconds', $baselineSeconds),
                 'fake', $ttl, 'open', $now, NULL, NULL
          FROM c
          WHERE c.requests::float8 >= ${s.spikeFactor}::float8 * c.expected
            AND c.sent >= ${s.minSent}
            AND c.verified::float8 <= c.sent * ${s.maxConversion}::float8
            AND (c.b_sent < ${s.minBaselineSent}
                 OR c.verified::float8 * c.b_sent <= c.b_verified::float8 * c.sent * ${s.conversionDrop}::float8)
            AND $unlisted
          ORDER BY c.requests DESC
          LIMIT ${run.maxProposals}
          ON CONFLICT (tenant_id, kind, value) WHERE status = 'open' DO NOTHING
        """.update.run())

  override def detectFakeVerifies(now: Instant, s: FakeVerify, run: Run): Task[Option[Int]] =
    xa.transactMeasured("detect-antifraud-fake-verifies"):
      if !guarded(FakeVerifyLock, run.statementTimeout) then None
      else
        val to = windowEnd(now, run.settle)
        val from = to.minusSeconds(s.window.getSeconds)
        val windowSeconds = s.window.getSeconds
        val ttl = run.ttl.getSeconds
        val unlisted = notProposedOrListed(ProposalKind.fake_verify, AntifraudValueType.prefix, now, run.cooldown)
        Some(sql"""
          WITH c AS (
            SELECT tenant_id, dest_prefix AS value, SUM(fake_verifies) AS fake_verifies, SUM(verified) AS verified,
                   SUM(sent) AS sent, SUM(requests) AS requests
            FROM af_dest_stats
            WHERE minute >= $from AND minute < $to AND tenant_id <> ''
            GROUP BY tenant_id, dest_prefix
            HAVING SUM(fake_verifies) >= ${s.minFakeVerifies} AND SUM(verified) <= ${s.maxVerified}
            ORDER BY SUM(fake_verifies) DESC
            LIMIT ${run.candidates}
          )
          INSERT INTO af_proposal (id, tenant_id, kind, value_type, value, evidence, suggested_action, ttl_seconds, status,
                                   created_at, decided_by, decided_at)
          SELECT gen_random_uuid(), c.tenant_id, 'fake_verify', 'prefix', c.value,
                 jsonb_build_object(
                   'fake_verifies', c.fake_verifies, 'verified', c.verified, 'sent', c.sent, 'requests', c.requests,
                   'window_seconds', $windowSeconds),
                 'deny', $ttl, 'open', $now, NULL, NULL
          FROM c
          WHERE $unlisted
          ORDER BY c.fake_verifies DESC
          LIMIT ${run.maxProposals}
          ON CONFLICT (tenant_id, kind, value) WHERE status = 'open' DO NOTHING
        """.update.run())

  override def expireOpen(before: Instant, limit: Int, statementTimeout: Duration): Task[Int] =
    xa.transactMeasured("expire-antifraud-proposals"):
      sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()
      sql"""
        UPDATE af_proposal SET status = 'expired'
        WHERE id IN (SELECT id FROM af_proposal WHERE status = 'open' AND created_at < $before ORDER BY created_at LIMIT $limit)
      """.update.run()

  override def prune(before: Instant, limit: Int, statementTimeout: Duration): Task[Int] =
    xa.transactMeasured("prune-antifraud-proposals"):
      sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()
      sql"""
        DELETE FROM af_proposal
        WHERE id IN (
          SELECT id FROM af_proposal
          WHERE status <> 'open' AND created_at < $before
          ORDER BY created_at LIMIT $limit
        )
      """.update.run()

object PostgresAntifraudProposalRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudProposalRepository] =
    ZLayer.fromFunction(PostgresAntifraudProposalRepository(_))

  /** Next to the locks of `PostgresAntifraudEventRepository` and `PostgresVelocityRepository`. */
  private val PrefixAnomalyLock = 7430004L
  private val FakeVerifyLock = 7430005L

  /** The newest whole minute the detectors read: the rollup of a later one may still be changing. */
  private[antifraud] def windowEnd(now: Instant, settle: Duration): Instant =
    now.minusSeconds(settle.getSeconds).truncatedTo(ChronoUnit.MINUTES)
