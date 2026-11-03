package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.antifraud.AntifraudDetectors.{FakeVerify, PrefixAnomaly, Run}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AntifraudProposalRepository]] promises of its detectors and housekeeping. The statistics, proposals and list
  * entries a case starts from are written through [[AntifraudProposalRepositorySpec.Fixtures]], which the storage's runner
  * implements; the storage adds in `implementationCases` what it alone promises (giving way to a run in progress). */
trait AntifraudProposalRepositorySpec extends DatabaseSpecBase[AntifraudProposalRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudProposalRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    val f = env.fixtures
    def prefixes = f.proposals.map(_.map(p => s"${p.tenantId}/${p.value}"))

    /** Ten requests a minute over the window, nearly all sent and almost none verified, after a quiet day that converted. */
    def attacked(tenant: String, prefix: String, verified: Int = 2): Task[Unit] =
      f.stats(tenant, prefix, baselineStart, windowStart, requests = 1, sent = 1, verified = 1) *>
        f.stats(tenant, prefix, windowStart, windowEnd, requests = 10, sent = 7, verified = 0) *>
        f.stats(tenant, prefix, windowEnd.minusSeconds(60), windowEnd, requests = 10, sent = 7, verified = verified)
    List(
      suite("prefix anomaly")(
        test("proposes a range whose requests jumped and whose conversion fell, with the evidence") {
          for
            _ <- attacked("tenant-a", "4915")
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
            found <- f.proposals
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
            _ <- f.stats("tenant-a", "9988", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
            found <- prefixes
          yield assertTrue(made == Some(1), found == List("tenant-a/9988"))
        },
        test("leaves a spike that converts as usual alone") {
          for
            _ <- f.stats("tenant-a", "4915", baselineStart, windowStart, requests = 1, sent = 1, verified = 1)
            _ <- f.stats("tenant-a", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 6)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          yield assertTrue(made == Some(0))
        },
        test("leaves a range that does not convert but has not grown alone") {
          for
            _ <- f.stats("tenant-a", "4915", baselineStart, windowStart, requests = 10, sent = 7, verified = 0)
            _ <- f.stats("tenant-a", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          yield assertTrue(made == Some(0))
        },
        test("leaves a range alone that is below the minimum of requests, or that sent too few codes to cost anything") {
          for
            _ <- f.stats("tenant-a", "1111", windowStart, windowEnd, requests = 2, sent = 2, verified = 0)
            _ <- f.stats("tenant-a", "2222", windowStart, windowEnd, requests = 10, sent = 1, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          yield assertTrue(made == Some(0))
        },
        test("does not read the minutes that are still settling, nor events of no tenant") {
          for
            _ <- f.stats("tenant-a", "4915", windowEnd, windowEnd.plusSeconds(120), requests = 100, sent = 70, verified = 0)
            _ <- f.stats("", "4916", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
          yield assertTrue(made == Some(0))
        },
        test("keeps tenants apart") {
          for
            _ <- attacked("tenant-a", "4915")
            _ <- f.stats("tenant-b", "4915", windowStart, windowEnd, requests = 10, sent = 7, verified = 6)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
            found <- prefixes
          yield assertTrue(made == Some(1), found == List("tenant-a/4915"))
        },
        test("does not propose a value that is already open, and proposes it again once the open one is gone") {
          for
            _ <- attacked("tenant-a", "4915")
            first <- repository.detectPrefixAnomalies(now, anomaly, limits)
            second <- repository.detectPrefixAnomalies(now.plusSeconds(60), anomaly, limits)
            openOne <- f.proposals
            expired <- repository.expireOpen(now.plusSeconds(1), 10, 10.seconds)
            third <- repository.detectPrefixAnomalies(now.plusSeconds(60), anomaly, limits)
            all <- f.proposals
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
            _ <- attacked("tenant-a", "4915")
            results <- ZIO.foreachPar(1 to 6)(_ => repository.detectPrefixAnomalies(now, anomaly, limits))
            found <- f.proposals
          yield assertTrue(results.flatten.sum == 1, found.size == 1)
        },
        test("does not propose again what was rejected lately, but does once the cooldown has passed") {
          for
            _ <- attacked("tenant-a", "4915")
            _ <- f.propose("tenant-a", "prefix_anomaly", "4915", "rejected", now.minusSeconds(7200), Some(now.minusSeconds(3600)))
            during <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(cooldown = 2.hours))
            after <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(cooldown = 30.minutes))
          yield assertTrue(during == Some(0), after == Some(1))
        },
        test("does not propose what a list entry covers, whichever list, and does when the entry is expired or belongs to another tenant") {
          for
            _ <- attacked("tenant-a", "4915")
            _ <- attacked("tenant-a", "4916")
            _ <- attacked("tenant-a", "4917")
            _ <- attacked("tenant-a", "4918")
            _ <- f.listed("49", None, None)
            _ <- f.listed("4916", Some("tenant-b"), None, "allow")
            _ <- f.listed("4917", Some("tenant-a"), Some(now.minusSeconds(60)))
            _ <- f.listed("4918", Some("tenant-a"), Some(now.plusSeconds(3600)), "allow")
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
            found <- prefixes
          yield assertTrue(made == Some(0), found.isEmpty)
        },
        test("proposes what only an expired entry, or another tenant's, or a shorter unrelated one covers") {
          for
            _ <- attacked("tenant-a", "4916")
            _ <- attacked("tenant-a", "4917")
            _ <- attacked("tenant-a", "4918")
            _ <- f.listed("4916", Some("tenant-b"), None)
            _ <- f.listed("4917", Some("tenant-a"), Some(now.minusSeconds(60)))
            _ <- f.listed("44", None, None)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits)
            found <- prefixes
          yield assertTrue(made == Some(3), found == List("tenant-a/4916", "tenant-a/4917", "tenant-a/4918"))
        },
        test("makes no more than the cap, the busiest first") {
          for
            _ <- f.stats("tenant-a", "1001", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
            _ <- f.stats("tenant-a", "1002", windowStart, windowEnd, requests = 30, sent = 20, verified = 0)
            _ <- f.stats("tenant-a", "1003", windowStart, windowEnd, requests = 20, sent = 14, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(maxProposals = 2))
            found <- prefixes
          yield assertTrue(made == Some(2), found == List("tenant-a/1002", "tenant-a/1003"))
        },
        test("looks closely at the busiest candidates only") {
          for
            _ <- f.stats("tenant-a", "1001", windowStart, windowEnd, requests = 10, sent = 7, verified = 0)
            _ <- f.stats("tenant-a", "1002", windowStart, windowEnd, requests = 30, sent = 20, verified = 0)
            made <- repository.detectPrefixAnomalies(now, anomaly, limits.copy(candidates = 1))
            found <- prefixes
          yield assertTrue(made == Some(1), found == List("tenant-a/1002"))
        },
      ),
      suite("fake verify")(
        test("proposes a range where codes were entered after a fake send and none was verified") {
          for
            _ <- f.stats("tenant-a", "4915", windowStart, windowStart.plusSeconds(5 * 60), requests = 3, sent = 0, verified = 0, fakeVerifies = 3)
            made <- repository.detectFakeVerifies(now, fakeVerify, limits)
            found <- f.proposals
          yield assertTrue(
            made == Some(1),
            found.map(p => (p.tenantId, p.kind, p.value, p.suggestedAction, p.status)) ==
              List(("tenant-a", "fake_verify", "4915", "deny", "open")),
            found.head.evidence.contains("\"fake_verifies\": 15"),
          )
        },
        test("leaves a range alone with few of them, or where real codes were verified, or of no tenant") {
          for
            _ <- f.stats("tenant-a", "1111", windowStart, windowStart.plusSeconds(240), requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            _ <- f.stats("tenant-a", "2222", windowStart, windowEnd, requests = 1, sent = 1, verified = 1, fakeVerifies = 1)
            _ <- f.stats("", "3333", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            made <- repository.detectFakeVerifies(now, fakeVerify, limits)
          yield assertTrue(made == Some(0))
        },
        test("counts only the window, and not the minutes still settling") {
          for
            _ <- f.stats(
              "tenant-a",
              "1111",
              windowStart.minusSeconds(3600),
              windowStart.minusSeconds(3600 - 600),
              requests = 1,
              sent = 0,
              verified = 0,
              fakeVerifies = 1,
            )
            _ <- f.stats("tenant-a", "2222", windowEnd, windowEnd.plusSeconds(600), requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            made <- repository.detectFakeVerifies(now, fakeVerify, limits)
          yield assertTrue(made == Some(0))
        },
        test("does not repeat an open proposal, and does not propose a covered range") {
          for
            _ <- f.stats("tenant-a", "4915", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            _ <- f.stats("tenant-a", "4916", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            _ <- f.listed("4916", None, None, "fake")
            first <- repository.detectFakeVerifies(now, fakeVerify, limits)
            second <- repository.detectFakeVerifies(now, fakeVerify, limits)
            found <- prefixes
          yield assertTrue(first == Some(1), second == Some(0), found == List("tenant-a/4915"))
        },
        test("an open proposal of the other detector does not stop this one") {
          for
            _ <- f.stats("tenant-a", "4915", windowStart, windowEnd, requests = 1, sent = 0, verified = 0, fakeVerifies = 1)
            _ <- f.propose("tenant-a", "prefix_anomaly", "4915", "open", now)
            made <- repository.detectFakeVerifies(now, fakeVerify, limits)
          yield assertTrue(made == Some(1))
        },
      ),
      suite("housekeeping")(
        test("expires open proposals by age, in batches, and removes old decided ones, in batches, leaving the open and the recent") {
          for
            _ <- f.propose("t", "prefix_anomaly", "1", "open", now.minusSeconds(100000))
            _ <- f.propose("t", "prefix_anomaly", "2", "open", now.minusSeconds(100000))
            _ <- f.propose("t", "prefix_anomaly", "3", "open", now)
            _ <- f.propose("t", "prefix_anomaly", "4", "approved", now.minusSeconds(200000), Some(now.minusSeconds(190000)))
            _ <- f.propose("t", "prefix_anomaly", "5", "rejected", now.minusSeconds(200000), Some(now.minusSeconds(190000)))
            _ <- f.propose("t", "prefix_anomaly", "6", "rejected", now, Some(now))
            expiredFirst <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
            expiredSecond <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
            expiredThird <- repository.expireOpen(now.minusSeconds(3600), 1, 10.seconds)
            prunedFirst <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
            prunedSecond <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
            prunedThird <- repository.prune(now.minusSeconds(100), 2, 10.seconds)
            left <- f.proposals.map(_.map(p => (p.value, p.status)))
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
    )

object AntifraudProposalRepositorySpec:
  case class Env(repository: AntifraudProposalRepository, fixtures: Fixtures)

  /** A proposal as stored, read back for the assertions: the storage's runner maps its rows to this. */
  case class Proposal(
      tenantId: String,
      kind: String,
      valueType: String,
      value: String,
      suggestedAction: String,
      ttlSeconds: Long,
      status: String,
      evidence: String,
  )

  /** What a case writes before it runs a detector, and reads after: rows the repository under test does not write itself
    * (the rollup's statistics, an earlier proposal, a list entry), so the storage's runner supplies them. */
  trait Fixtures:
    /** One row of the rollup for each minute of [from, to): the numbers are per minute. */
    def stats(
        tenant: String,
        prefix: String,
        from: Instant,
        to: Instant,
        requests: Int,
        sent: Int,
        verified: Int,
        fakeVerifies: Int = 0,
    ): Task[Unit]

    /** Every proposal, ordered by tenant, kind, value and creation. */
    def proposals: Task[List[Proposal]]

    def propose(tenant: String, kind: String, value: String, status: String, createdAt: Instant, decidedAt: Option[Instant] = None): Task[Unit]

    def listed(value: String, tenant: Option[String], expiresAt: Option[Instant], listType: String = "deny"): Task[Unit]

  // The detectors read the whole minutes before `now` minus the settling time: [11:43, 11:58) here.
  val now: Instant = Instant.parse("2026-10-21T12:00:30Z")
  val windowStart: Instant = Instant.parse("2026-10-21T11:43:00Z")
  val windowEnd: Instant = Instant.parse("2026-10-21T11:58:00Z")
  val baselineStart: Instant = windowStart.minusSeconds(24 * 3600)

  private val settings = AntifraudTestSettings.analytics
  val anomaly: PrefixAnomaly = settings.prefixAnomaly
  val fakeVerify: FakeVerify = settings.fakeVerify

  val limits: Run = Run(
    settle = settings.settle,
    statementTimeout = 10.seconds,
    maxProposals = 20,
    candidates = 200,
    cooldown = 24.hours,
    ttl = 24.hours,
  )
