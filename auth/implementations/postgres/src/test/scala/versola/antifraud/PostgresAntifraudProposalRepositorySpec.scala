package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.antifraud.AntifraudDetectors.{FakeVerify, PrefixAnomaly, Run}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PostgresAntifraudProposalRepositorySpec extends PostgresSpec:

  private case class Proposal(
      tenantId: String,
      kind: String,
      valueType: String,
      value: String,
      suggestedAction: String,
      ttlSeconds: Long,
      status: String,
      evidence: String,
  ) derives DbCodec

  // The detectors read the whole minutes before `now` minus the settling time: [11:43, 11:58) here.
  private val now = Instant.parse("2026-10-21T12:00:30Z")
  private val windowStart = Instant.parse("2026-10-21T11:43:00Z")
  private val windowEnd = Instant.parse("2026-10-21T11:58:00Z")
  private val baselineStart = windowStart.minusSeconds(24 * 3600)

  private val settings = AntifraudDetectors.Settings.default
  private val anomaly: PrefixAnomaly = settings.prefixAnomaly
  private val fakeVerify: FakeVerify = settings.fakeVerify

  private val limits = Run(
    settle = settings.settle,
    statementTimeout = 10.seconds,
    maxProposals = 20,
    candidates = 200,
    cooldown = 24.hours,
    ttl = 24.hours,
  )

  /** `perMinute` rows of the rollup for each minute of [from, to), one per minute: the numbers are per minute. */
  private def stats(
      tenant: String,
      prefix: String,
      from: Instant,
      to: Instant,
      requests: Int,
      sent: Int,
      verified: Int,
      fakeVerifies: Int = 0,
  ): ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect:
        sql"""
          INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled, provider_errors,
                                     verified, wrong_codes, fake_verifies)
          SELECT $tenant, $prefix, m, $requests, $sent, 0, 0, 0, $verified, 0, $fakeVerifies
          FROM generate_series($from::timestamptz, ($to::timestamptz - interval '1 minute'), interval '1 minute') AS m
          ON CONFLICT (tenant_id, dest_prefix, minute) DO UPDATE SET requests = EXCLUDED.requests, sent = EXCLUDED.sent,
            verified = EXCLUDED.verified, fake_verifies = EXCLUDED.fake_verifies
        """.update.run()
      .unit

  /** Ten requests a minute over the window, nearly all sent and almost none verified, after a quiet day that converted. */
  private def attacked(tenant: String, prefix: String, verified: Int = 2): ZIO[TransactorZIO, Throwable, Unit] =
    stats(tenant, prefix, baselineStart, windowStart, requests = 1, sent = 1, verified = 1) *>
      stats(tenant, prefix, windowStart, windowEnd, requests = 10, sent = 7, verified = 0) *>
      stats(tenant, prefix, windowEnd.minusSeconds(60), windowEnd, requests = 10, sent = 7, verified = verified)

  private def proposals: ZIO[TransactorZIO, Throwable, List[Proposal]] =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect:
        sql"""SELECT tenant_id, kind, value_type, value, suggested_action, ttl_seconds, status, evidence::text
              FROM af_proposal ORDER BY tenant_id, kind, value, created_at""".query[Proposal].run().toList

  private def reset: ZIO[TransactorZIO, Throwable, PostgresAntifraudProposalRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"DELETE FROM af_dest_stats".update.run()
        sql"DELETE FROM af_proposal".update.run()
        sql"DELETE FROM af_list_entry".update.run()
    yield PostgresAntifraudProposalRepository(xa)

  private def prefixes: ZIO[TransactorZIO, Throwable, List[String]] = proposals.map(_.map(p => s"${p.tenantId}/${p.value}"))

  private def propose(
      tenant: String,
      kind: String,
      value: String,
      status: String,
      createdAt: Instant,
      decidedAt: Option[Instant] = None,
  ): ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect:
        sql"""
          INSERT INTO af_proposal (id, tenant_id, kind, value_type, value, evidence, suggested_action, ttl_seconds, status,
                                   created_at, decided_by, decided_at)
          VALUES (${UUID.randomUUID()}, $tenant, $kind, 'prefix', $value, '{}'::jsonb, 'deny', 3600, $status, $createdAt,
                  ${decidedAt.map(_ => "someone")}, $decidedAt)
        """.update.run()
      .unit

  private def listed(
      value: String,
      tenant: Option[String],
      expiresAt: Option[Instant],
      listType: String = "deny",
  ): ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect:
        sql"""
          INSERT INTO af_list_entry (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at,
                                     hit_count, last_hit_at)
          VALUES (${UUID.randomUUID()}, $listType, 'prefix', $value, $tenant, $expiresAt, NULL, 'manual', FALSE, 'op', $now, 0, NULL)
        """.update.run()
      .unit

  def spec = suite("PostgresAntifraudProposalRepository")(
    suite("prefix anomaly")(
      test("proposes a range whose requests jumped and whose conversion fell, with the evidence") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          found <- proposals
        yield assertTrue(
          made == Some(1),
          found.map(p => (p.tenantId, p.kind, p.valueType, p.value, p.suggestedAction, p.ttlSeconds, p.status)) ==
            List(("tenant-a", "prefix_anomaly", "prefix", "4915", "fake", 86400L, "open")),
          found.head.evidence.contains("\"requests\": 150"),
          found.head.evidence.contains("\"verified\": 2"),
        )
      },
      test("proposes a range with no baseline at all when it is busy and does not convert") {
        for
          repository <- reset
          _ <- stats("tenant-a", "9988", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          found <- prefixes
        yield assertTrue(made == Some(1), found == List("tenant-a/9988"))
      },
      test("leaves a spike that converts as usual alone") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", baselineStart, windowStart, requests = 1, sent = 1, verified = 1)
          _ <- stats("tenant-a", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 6)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(made == Some(0))
      },
      test("leaves a range that does not convert but has not grown alone") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", baselineStart, windowStart, requests = 10, sent = 7, verified = 0)
          _ <- stats("tenant-a", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(made == Some(0))
      },
      test("leaves a range alone that is below the minimum of requests, or that sent too few codes to cost anything") {
        for
          repository <- reset
          _ <- stats("tenant-a", "1111", windowStart, windowEnd, requests = 2, sent = 2, verified = 0)
          _ <- stats("tenant-a", "2222", windowStart, windowEnd, requests = 10, sent = 1, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(made == Some(0))
      },
      test("does not read the minutes that are still settling, nor events of no tenant") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", windowEnd, windowEnd.plusSeconds(120), requests = 100, sent = 70, verified = 0)
          _ <- stats("", "4916", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(made == Some(0))
      },
      test("keeps tenants apart") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          _ <- stats("tenant-b", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 6)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          found <- prefixes
        yield assertTrue(made == Some(1), found == List("tenant-a/4915"))
      },
      test("does not propose a value that is already open, and proposes it again once the open one is gone") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          first <- repository.detectPrefixAnomalies(now, anomaly, limits)
          second <- repository.detectPrefixAnomalies(now.plusSeconds(60), anomaly, limits)
          openOne <- proposals
          expired <- repository.expireOpen(now.plusSeconds(1), 10, 10.seconds)
          third <- repository.detectPrefixAnomalies(now.plusSeconds(60), anomaly, limits)
          all <- proposals
        yield assertTrue(
          first == Some(1),
          second == Some(0),
          openOne.size == 1,
          expired == 1,
          third == Some(1),
          all.map(_.status).sorted == List("expired", "open"),
        )
      },
      test("two instances at once make one proposal") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          results <- ZIO.foreachPar(1 to 6)(_ => repository.detectPrefixAnomalies(now, anomaly, limits))
          found <- proposals
        yield assertTrue(results.flatten.sum == 1, found.size == 1)
      },
      test("gives way to an instance that is running it") {
        for
          repository <- reset
          xa <- ZIO.service[TransactorZIO]
          _ <- attacked("tenant-a", "4915")
          holder <- xa.transact:
            sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock(7430004)) l".query[Int].run()
            Thread.sleep(1500)
          .fork
          _ <- Live.live(ZIO.sleep(400.millis))
          skipped <- repository.detectPrefixAnomalies(now, anomaly, limits)
          _ <- holder.join
          after <- repository.detectPrefixAnomalies(now, anomaly, limits)
        yield assertTrue(skipped.isEmpty, after == Some(1))
      },
      test("does not propose again what was rejected lately, but does once the cooldown has passed") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          _ <- propose("tenant-a", "prefix_anomaly", "4915", "rejected", now.minusSeconds(7200), Some(now.minusSeconds(3600)))
          during <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(cooldown = 2.hours))
          after <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(cooldown = 30.minutes))
        yield assertTrue(during == Some(0), after == Some(1))
      },
      test("does not propose what a list entry covers, whichever list, and does when the entry is expired or belongs to another tenant") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4915")
          _ <- attacked("tenant-a", "4916")
          _ <- attacked("tenant-a", "4917")
          _ <- attacked("tenant-a", "4918")
          _ <- listed("49", None, None)
          _ <- listed("4916", Some("tenant-b"), None, "allow")
          _ <- listed("4917", Some("tenant-a"), Some(now.minusSeconds(60)))
          _ <- listed("4918", Some("tenant-a"), Some(now.plusSeconds(3600)), "allow")
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          found <- prefixes
        yield assertTrue(made == Some(0), found.isEmpty)
      },
      test("proposes what only an expired entry, or another tenant's, or a shorter unrelated one covers") {
        for
          repository <- reset
          _ <- attacked("tenant-a", "4916")
          _ <- attacked("tenant-a", "4917")
          _ <- attacked("tenant-a", "4918")
          _ <- listed("4916", Some("tenant-b"), None)
          _ <- listed("4917", Some("tenant-a"), Some(now.minusSeconds(60)))
          _ <- listed("44", None, None)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          found <- prefixes
        yield assertTrue(made == Some(3), found == List("tenant-a/4916", "tenant-a/4917", "tenant-a/4918"))
      },
      test("makes no more than the cap, the busiest first") {
        for
          repository <- reset
          _ <- stats("tenant-a", "1001", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          _ <- stats("tenant-a", "1002", windowStart, windowEnd, requests = 30, sent = 20, verified = 0)
          _ <- stats("tenant-a", "1003", windowStart, windowEnd, requests = 20, sent = 14, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(maxProposals = 2))
          found <- prefixes
        yield assertTrue(made == Some(2), found == List("tenant-a/1002", "tenant-a/1003"))
      },
      test("looks closely at the busiest candidates only") {
        for
          repository <- reset
          _ <- stats("tenant-a", "1001", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
          _ <- stats("tenant-a", "1002", windowStart, windowEnd, requests = 30, sent = 20, verified = 0)
          made <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(candidates = 1))
          found <- prefixes
        yield assertTrue(made == Some(1), found == List("tenant-a/1002"))
      },
    ),
    suite("fake verify")(
      test("proposes a range where codes were entered after a fake send and none was verified") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", windowStart, windowStart.plusSeconds(5 * 60), requests = 3, sent = 0, verified = 0, fakeVerifies = 3)
          made <- repository.detectFakeVerifies(now, fakeVerify, limits)
          found <- proposals
        yield assertTrue(
          made == Some(1),
          found.map(p => (p.tenantId, p.kind, p.value, p.suggestedAction, p.status)) ==
            List(("tenant-a", "fake_verify", "4915", "deny", "open")),
          found.head.evidence.contains("\"fake_verifies\": 15"),
        )
      },
      test("leaves a range alone with few of them, or where real codes were verified, or of no tenant") {
        for
          repository <- reset
          _ <- stats("tenant-a", "1111", windowStart, windowStart.plusSeconds(240), requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          _ <- stats("tenant-a", "2222", windowStart, windowEnd, requests = 1, sent = 1, verified = 1, fakeVerifies = 1)
          _ <- stats("", "3333", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          made <- repository.detectFakeVerifies(now, fakeVerify, limits)
        yield assertTrue(made == Some(0))
      },
      test("counts only the window, and not the minutes still settling") {
        for
          repository <- reset
          _ <- stats(
            "tenant-a",
            "1111",
            windowStart.minusSeconds(3600),
            windowStart.minusSeconds(3600 - 600),
            requests = 1,
            sent = 0,
            verified = 0,
            fakeVerifies = 1,
          )
          _ <- stats("tenant-a", "2222", windowEnd, windowEnd.plusSeconds(600), requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          made <- repository.detectFakeVerifies(now, fakeVerify, limits)
        yield assertTrue(made == Some(0))
      },
      test("does not repeat an open proposal, and does not propose a covered range") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          _ <- stats("tenant-a", "4916", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          _ <- listed("4916", None, None, "fake")
          first <- repository.detectFakeVerifies(now, fakeVerify, limits)
          second <- repository.detectFakeVerifies(now, fakeVerify, limits)
          found <- prefixes
        yield assertTrue(first == Some(1), second == Some(0), found == List("tenant-a/4915"))
      },
      test("an open proposal of the other detector does not stop this one") {
        for
          repository <- reset
          _ <- stats("tenant-a", "4915", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
          _ <- propose("tenant-a", "prefix_anomaly", "4915", "open", now)
          made <- repository.detectFakeVerifies(now, fakeVerify, limits)
        yield assertTrue(made == Some(1))
      },
    ),
    suite("housekeeping")(
      test("expires open proposals by age, in batches, and removes old decided ones, in batches, leaving the open and the recent") {
        for
          repository <- reset
          _ <- propose("t", "prefix_anomaly", "1", "open", now.minusSeconds(100000))
          _ <- propose("t", "prefix_anomaly", "2", "open", now.minusSeconds(100000))
          _ <- propose("t", "prefix_anomaly", "3", "open", now)
          _ <- propose("t", "prefix_anomaly", "4", "approved", now.minusSeconds(200000), Some(now.minusSeconds(190000)))
          _ <- propose("t", "prefix_anomaly", "5", "rejected", now.minusSeconds(200000), Some(now.minusSeconds(190000)))
          _ <- propose("t", "prefix_anomaly", "6", "rejected", now, Some(now))
          expiredFirst <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
          expiredSecond <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
          expiredThird <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
          prunedFirst <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
          prunedSecond <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
          prunedThird <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
          left <- proposals.map(_.map(p => (p.value, p.status)))
        yield assertTrue(
          expiredFirst == 1,
          expiredSecond == 1,
          expiredThird == 0,
          prunedFirst == 2,
          prunedSecond == 2,
          prunedThird == 0,
          left == List(("3", "open"), ("6", "rejected")),
        )
      },
    ),
  ) @@ TestAspect.sequential
