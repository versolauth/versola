package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.{Instant, ZoneOffset}
import java.util.UUID

/** What every [[AntifraudListRepository]] promises: entries stored with their audit, refreshed on a repeat and kept apart by scope,
  * filtered and paged, expired and pruned in batches, counted for the cache's version, and previewed against the event log and
  * its rollup. A preview reads the events the real [[AntifraudEventRepository]] wrote and the statistics a case gets from
  * [[AntifraudListRepositorySpec.Fixtures]], which the storage's runner implements; the storage adds in `implementationCases`
  * what it alone promises (a statement timeout, a row of a kind it stores and a version does not know). */
trait AntifraudListRepositorySpec extends DatabaseSpecBase[AntifraudListRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudListRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    val events = env.events
    val resets = env.resets
    val f = env.fixtures
    List(
      test("stores an entry with every column and reads it back, with an audit record") {
        for
          tenant = Some(TenantId("tenant-a"))
          e = entry(
            listType = AntifraudListType.fake,
            valueType = AntifraudValueType.cidr,
            value = "10.0.0.0/8",
            tenantId = tenant,
            expiresAt = Some(at.plusSeconds(3600)),
          )
          stored <- repository.upsert(e, "alice", at)
          listed <- repository.list(AntifraudListFilter.all, 10, 0)
          audit <- repository.audit(None, 10, None)
        yield assertTrue(
          stored == e,
          listed == List(e),
          audit.map(a => (a.action, a.actor, a.entryId, a.listType, a.valueType, a.value, a.tenantId, a.expiresAt, a.reason)) ==
            List((
              AntifraudListAction.create,
              "alice",
              e.id,
              AntifraudListType.fake,
              AntifraudValueType.cidr,
              "10.0.0.0/8",
              tenant,
              e.expiresAt,
              e.reason,
            )),
        )
      },
      test("adding the same value in the same scope again refreshes it and keeps the id; another scope is another entry") {
        for
          first <- repository.upsert(entry(), "alice", at)
          again <- repository.upsert(
            entry().copy(expiresAt = Some(at.plusSeconds(60)), reason = Some("again"), source = "rule", createdBy = "bob"),
            "bob",
            at.plusSeconds(1),
          )
          tenantScoped <- repository.upsert(entry(tenantId = Some(TenantId("t"))), "alice", at)
          otherList <- repository.upsert(entry(AntifraudListType.watch), "alice", at)
          listed <- repository.list(AntifraudListFilter.all, 10, 0)
          audit <- repository.audit(Some(first.id), 10, None)
        yield assertTrue(
          again.id == first.id,
          again.expiresAt.contains(at.plusSeconds(60)),
          again.reason.contains("again"),
          again.source == "rule",
          again.createdBy == "alice",
          tenantScoped.id != first.id,
          otherList.id != first.id,
          listed.size == 3,
          audit.map(_.actor) == List("bob", "alice"),
        )
      },
      test("filters by list, value type and scope; pages") {
        for
          global <- repository.upsert(entry(value = "a"), "x", at)
          scoped <- repository.upsert(entry(value = "b", tenantId = Some(TenantId("t"))), "x", at)
          watch <- repository.upsert(entry(AntifraudListType.watch, AntifraudValueType.asn, "64500"), "x", at)
          denies <- repository.list(AntifraudListFilter.all.copy(listType = Some(AntifraudListType.deny)), 10, 0)
          asns <- repository.list(AntifraudListFilter.all.copy(valueType = Some(AntifraudValueType.asn)), 10, 0)
          globals <- repository.list(AntifraudListFilter.all.copy(tenantId = Some(None)), 10, 0)
          tenants <- repository.list(AntifraudListFilter.all.copy(tenantId = Some(Some(TenantId("t")))), 10, 0)
          page <- repository.list(AntifraudListFilter.all, 2, 2)
        yield assertTrue(
          denies.map(_.id).toSet == Set(global.id, scoped.id),
          asns.map(_.id) == List(watch.id),
          globals.map(_.id).toSet == Set(global.id, watch.id),
          tenants.map(_.id) == List(scoped.id),
          page.size == 1,
        )
      },
      test("loads what has not expired, as the cache needs it") {
        for
          forever <- repository.upsert(entry(value = "forever"), "x", at)
          later <- repository.upsert(entry(value = "later", expiresAt = Some(at.plusSeconds(10))), "x", at)
          _ <- repository.upsert(entry(value = "gone", expiresAt = Some(at)), "x", at)
          _ <- repository.upsert(entry(value = "past", expiresAt = Some(at.minusSeconds(10))), "x", at)
          active <- repository.loadActive(at)
        yield assertTrue(
          active.map(_.id).toSet == Set(forever.id, later.id),
          active.find(_.id == later.id).flatMap(_.expiresAt).contains(at.plusSeconds(10)),
        )
      },
      test("a listing and the audit can be limited to what a tenant sees: the global entries and its own") {
        for
          a = Some(TenantId("tenant-a"))
          b = Some(TenantId("tenant-b"))
          global <- repository.upsert(entry(value = "g"), "x", at)
          own <- repository.upsert(entry(value = "a", tenantId = a), "x", at.plusSeconds(1))
          other <- repository.upsert(entry(value = "b", tenantId = b), "x", at.plusSeconds(2))
          seenByA <- repository.list(AntifraudListFilter.all.copy(visibleTo = a), 10, 0)
          seenByB <- repository.list(AntifraudListFilter.all.copy(visibleTo = b), 10, 0)
          everything <- repository.list(AntifraudListFilter.all, 10, 0)
          onlyOwn <- repository.list(AntifraudListFilter(listType = None, valueType = None, tenantId = Some(a), visibleTo = a), 10, 0)
          noneOfOthers <- repository.list(AntifraudListFilter(listType = None, valueType = None, tenantId = Some(b), visibleTo = a), 10, 0)
          auditA <- repository.audit(None, 10, a)
          auditAll <- repository.audit(None, 10, None)
          auditOfOthers <- repository.audit(Some(other.id), 10, a)
          auditOfOwn <- repository.audit(Some(own.id), 10, a)
          auditOfGlobal <- repository.audit(Some(global.id), 10, a)
        yield assertTrue(
          seenByA.map(_.id).toSet == Set(global.id, own.id),
          seenByB.map(_.id).toSet == Set(global.id, other.id),
          everything.size == 3,
          onlyOwn.map(_.id) == List(own.id),
          noneOfOthers.isEmpty,
          auditA.map(_.entryId).toSet == Set(global.id, own.id),
          auditAll.size == 3,
          auditOfOthers.isEmpty,
          auditOfOwn.size == 1,
          auditOfGlobal.size == 1,
        )
      },
      test("deletes an entry, records who did, and says so when there was none") {
        for
          e <- repository.upsert(entry(), "alice", at)
          removed <- repository.delete(e.id, "bob", at.plusSeconds(5))
          again <- repository.delete(e.id, "bob", at.plusSeconds(6))
          listed <- repository.list(AntifraudListFilter.all, 10, 0)
          audit <- repository.audit(Some(e.id), 10, None)
        yield assertTrue(
          removed,
          !again,
          listed.isEmpty,
          audit.map(a => (a.action, a.actor, a.value)) == List(
            (AntifraudListAction.delete, "bob", "dev-1"),
            (AntifraudListAction.create, "alice", "dev-1"),
          ),
        )
      },
      test("removes expired entries and records each as expired") {
        for
          live <- repository.upsert(entry(value = "live", expiresAt = Some(at.plusSeconds(60))), "x", at)
          old <- repository.upsert(entry(value = "old", expiresAt = Some(at.plusSeconds(1))), "x", at)
          n <- repository.purgeExpired(at.plusSeconds(2), 1000)
          listed <- repository.list(AntifraudListFilter.all, 10, 0)
          audit <- repository.audit(Some(old.id), 10, None)
        yield assertTrue(
          n == 1,
          listed.map(_.id) == List(live.id),
          audit.map(a => (a.action, a.actor)).head == (AntifraudListAction.expire, "system"),
        )
      },
      test("every change bumps the version and nothing else does") {
        for
          v0 <- repository.version
          e <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(1))), "x", at)
          v1 <- repository.version
          _ <- repository.addHits(List(ListHit(e.id, 1, at)))
          _ <- repository.list(AntifraudListFilter.all, 10, 0)
          v2 <- repository.version
          _ <- repository.purgeExpired(at, 10)
          v3 <- repository.version
          _ <- repository.purgeExpired(at.plusSeconds(5), 10)
          v4 <- repository.version
          _ <- repository.purgeExpired(at.plusSeconds(5), 10)
          v5 <- repository.version
          _ <- repository.delete(e.id, "x", at)
          v6 <- repository.version
          e2 <- repository.upsert(entry(), "x", at)
          _ <- repository.delete(e2.id, "x", at)
          v7 <- repository.version
        yield assertTrue(v1 == v0 + 1, v2 == v1, v3 == v2, v4 == v3 + 1, v5 == v4, v6 == v5, v7 == v6 + 2)
      },
      test("purges and prunes in batches of at most the limit, oldest first, and does not touch what is not yet due") {
        for
          _ <- ZIO.foreachDiscard(1 to 5)(i => repository.upsert(entry(value = s"d$i", expiresAt = Some(at.plusSeconds(i))), "x", at.plusSeconds(i)))
          first <- repository.purgeExpired(at.plusSeconds(100), 2)
          second <- repository.purgeExpired(at.plusSeconds(100), 2)
          third <- repository.purgeExpired(at.plusSeconds(100), 2)
          fourth <- repository.purgeExpired(at.plusSeconds(100), 2)
          // 5 'create' audit records at +1..+5 s and 5 'expire' at +100 s
          none <- repository.pruneAudit(at, 10)
          some <- repository.pruneAudit(at.plusSeconds(50), 3)
          rest <- repository.pruneAudit(at.plusSeconds(50), 3)
          left <- repository.audit(None, 100, None)
        yield assertTrue(
          first == 2,
          second == 2,
          third == 1,
          fourth == 0,
          none == 0,
          some == 3,
          rest == 2,
          left.size == 5,
          left.forall(_.action == AntifraudListAction.expire),
        )
      },
      test("adds the hits to the counters, and keeps the latest time") {
        for
          e <- repository.upsert(entry(), "x", at)
          _ <- repository.addHits(List(ListHit(e.id, 3, at.plusSeconds(10)), ListHit(UUID.randomUUID(), 5, at)))
          _ <- repository.addHits(List(ListHit(e.id, 2, at.plusSeconds(5))))
          listed <- repository.list(AntifraudListFilter.all, 10, 0)
        yield assertTrue(listed.map(l => (l.hitCount, l.lastHitAt)) == List((5L, Some(at.plusSeconds(10)))))
      },
      test("keeps the shadow flag, refreshes it on a repeat, and loads it for the cache") {
        for
          first <- repository.upsert(entry(shadow = true), "x", at)
          loaded <- repository.loadActive(at)
          again <- repository.upsert(entry(shadow = false), "x", at)
          found <- repository.find(first.id)
          missing <- repository.find(UUID.randomUUID())
        yield assertTrue(first.shadow, loaded.map(_.shadow) == List(true), !again.shadow, found.map(_.shadow).contains(false), missing.isEmpty)
      },
      test("previews what an entry would have matched of the event log, for the values the log has a column for") {
        for
          today = at.atZone(ZoneOffset.UTC).toLocalDate
          _ <- events.maintainPartitions(from = today.minusDays(2), days = 3, keepFrom = today.minusDays(30))
          ua = "Mozilla/5.0 (preview)"
          base = AntifraudEvent(
            id = UUID.randomUUID(),
            at = at.minusSeconds(60),
            tenantId = None,
            clientId = ClientId("c"),
            conversationId = UUID.randomUUID(),
            action = AntifraudAction.otp_request,
            outcome = AntifraudOutcome.sent,
            channel = AntifraudChannel.sms,
            subjectHash = "h1",
            destPrefix = Some("491512"),
            userAgent = Some(ua),
            decision = None,
            score = None,
            rules = Nil,
            wouldAction = None,
            asn = Some(24940),
            asnClass = Some("hosting"),
            country = Some("DE"),
          )
          _ <- events.insertBatch(
            List(
              base,
              base.copy(
                id = UUID.randomUUID(),
                subjectHash = "h2",
                destPrefix = Some("491612"),
                userAgent = Some("other"),
                asn = Some(64500),
                country = Some("KZ"),
              ),
              base.copy(
                id = UUID.randomUUID(),
                subjectHash = "h3",
                destPrefix = Some("33123"),
                userAgent = None,
                asn = None,
                asnClass = None,
                country = None,
              ),
              base.copy(id = UUID.randomUUID(), at = at.minusSeconds(48 * 3600), subjectHash = "h1"),
            ),
          )
          since = at.minusSeconds(3600)
          byPhone <- repository.preview(AntifraudValueType.phone_hash, "h1", None, since)
          byUa <- repository.preview(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex(ua), None, since)
          byIp <- repository.preview(AntifraudValueType.ip, "10.0.0.1", None, since)
          byAsn <- repository.preview(AntifraudValueType.asn, "24940", None, since)
          byOtherAsn <- repository.preview(AntifraudValueType.asn, "64500", None, since)
          byNoAsn <- repository.preview(AntifraudValueType.asn, "65000", None, since)
          byHugeAsn <- repository.preview(AntifraudValueType.asn, "4200000000", None, since)
          byCountry <- repository.preview(AntifraudValueType.country, "DE", None, since)
          byOtherCountry <- repository.preview(AntifraudValueType.country, "KZ", None, since)
          byNoCountry <- repository.preview(AntifraudValueType.country, "FR", None, since)
        yield assertTrue(
          byPhone == ListPreview(matched = Some(1), total = 3),
          byUa == ListPreview(matched = Some(1), total = 3),
          byIp == ListPreview(matched = None, total = 3),
          // The event of 48 hours ago is out of the window; the one with no ASN is in the total and not in the match.
          byAsn == ListPreview(matched = Some(1), total = 3),
          byOtherAsn == ListPreview(matched = Some(1), total = 3),
          byNoAsn == ListPreview(matched = Some(0), total = 3),
          byHugeAsn == ListPreview(matched = Some(0), total = 3),
          // The same for the country: the event of 48 hours ago is out of the window, the one with no country is in the total only.
          byCountry == ListPreview(matched = Some(1), total = 3),
          byOtherCountry == ListPreview(matched = Some(1), total = 3),
          byNoCountry == ListPreview(matched = Some(0), total = 3),
        )
      },
      test("a preview is over the events of the entry's tenant, and of all of them for a global entry") {
        val a = Some(TenantId("tenant-a"))
        val b = Some(TenantId("tenant-b"))
        for
          today = at.atZone(ZoneOffset.UTC).toLocalDate
          _ <- events.maintainPartitions(from = today.minusDays(2), days = 3, keepFrom = today.minusDays(30))
          base = AntifraudEvent(
            id = UUID.randomUUID(),
            at = at.minusSeconds(60),
            tenantId = a,
            clientId = ClientId("c"),
            conversationId = UUID.randomUUID(),
            action = AntifraudAction.otp_request,
            outcome = AntifraudOutcome.sent,
            channel = AntifraudChannel.sms,
            subjectHash = "h1",
            destPrefix = Some("491512"),
            userAgent = Some("Mozilla/5.0 (tenants)"),
            decision = None,
            score = None,
            rules = Nil,
            wouldAction = None,
            asn = None,
            asnClass = None,
            country = Some("DE"),
          )
          _ <- events.insertBatch(
            List(
              base,
              base.copy(id = UUID.randomUUID(), subjectHash = "h2"),
              base.copy(id = UUID.randomUUID(), tenantId = b),
              base.copy(id = UUID.randomUUID(), tenantId = b),
              base.copy(id = UUID.randomUUID(), tenantId = b),
              base.copy(id = UUID.randomUUID(), tenantId = None),
            ),
          )
          since = at.minusSeconds(3600)
          ua = ListSnapshot.sha256Hex("Mozilla/5.0 (tenants)")
          global <- repository.preview(AntifraudValueType.phone_hash, "h1", None, since)
          ofA <- repository.preview(AntifraudValueType.phone_hash, "h1", a, since)
          ofB <- repository.preview(AntifraudValueType.phone_hash, "h1", b, since)
          uaOfA <- repository.preview(AntifraudValueType.ua_hash, ua, a, since)
          ipOfB <- repository.preview(AntifraudValueType.ip, "10.0.0.1", b, since)
          countryOfA <- repository.preview(AntifraudValueType.country, "DE", a, since)
          countryGlobal <- repository.preview(AntifraudValueType.country, "DE", None, since)
        yield assertTrue(
          global == ListPreview(matched = Some(5), total = 6),
          ofA == ListPreview(matched = Some(1), total = 2),
          ofB == ListPreview(matched = Some(3), total = 3),
          uaOfA == ListPreview(matched = Some(2), total = 2),
          ipOfB == ListPreview(matched = None, total = 3),
          countryOfA == ListPreview(matched = Some(2), total = 2),
          countryGlobal == ListPreview(matched = Some(6), total = 6),
        )
      },
      test("a preview of a prefix of up to the statistics' length reads the rolled-up requests of the range, of the entry's tenant") {
        val since = at.minusSeconds(3600)
        for
          _ <- f.clearStats
          _ <- f.stats("tenant-a", "4915", since.plusSeconds(600), 30)
          _ <- f.stats("tenant-a", "4916", since.plusSeconds(660), 60)
          _ <- f.stats("tenant-b", "4915", since.plusSeconds(600), 10)
          // before the window
          _ <- f.stats("tenant-a", "4915", since.minusSeconds(60), 1000)
          global <- repository.preview(AntifraudValueType.prefix, "4915", None, since)
          ofA <- repository.preview(AntifraudValueType.prefix, "4915", Some(TenantId("tenant-a")), since)
          shorter <- repository.preview(AntifraudValueType.prefix, "49", Some(TenantId("tenant-a")), since)
          none <- repository.preview(AntifraudValueType.prefix, "33", Some(TenantId("tenant-a")), since)
          empty <- repository.preview(AntifraudValueType.prefix, "4915", Some(TenantId("nobody")), since)
        yield assertTrue(
          global == ListPreview(matched = Some(40), total = 100),
          ofA == ListPreview(matched = Some(30), total = 90),
          shorter == ListPreview(matched = Some(90), total = 90),
          none == ListPreview(matched = Some(0), total = 90),
          empty == ListPreview(matched = Some(0), total = 0),
        )
      },
      test(
        "a preview of a prefix longer than the statistics keep is counted for that prefix alone: two prefixes of one range have their own shares",
      ) {
        val a = Some(TenantId("tenant-a"))
        val since = at.minusSeconds(3600)
        for
          today = at.atZone(ZoneOffset.UTC).toLocalDate
          _ <- events.maintainPartitions(from = today.minusDays(2), days = 3, keepFrom = today.minusDays(30))
          _ <- f.clearStats
          // The rollup says the whole range 4915 is all of the traffic (the 4-digit bucket every longer prefix of it would be judged by).
          _ <- f.stats("tenant-a", "4915", since.plusSeconds(600), 1000)
          _ <- events.insertBatch(
            List.fill(2)(smsRequest(Some("491512"))) ++
              List.fill(18)(smsRequest(Some("491599"))) ++
              List.fill(80)(smsRequest(Some("333333"))),
          )
          narrow <- repository.preview(AntifraudValueType.prefix, "491512", a, since)
          broad <- repository.preview(AntifraudValueType.prefix, "491599", a, since)
          fiveDigits <- repository.preview(AntifraudValueType.prefix, "49159", a, since)
          absent <- repository.preview(AntifraudValueType.prefix, "491507", a, since)
          global <- repository.preview(AntifraudValueType.prefix, "491512", None, since)
        yield assertTrue(
          narrow == ListPreview(matched = Some(2), total = 100),
          broad == ListPreview(matched = Some(18), total = 100),
          fiveDigits == ListPreview(matched = Some(18), total = 100),
          absent == ListPreview(matched = Some(0), total = 100),
          global == narrow,
        )
      },
      test("a preview of a prefix of exactly the statistics' length still reads the rollup, not the event log") {
        val a = Some(TenantId("tenant-a"))
        val since = at.minusSeconds(3600)
        for
          today = at.atZone(ZoneOffset.UTC).toLocalDate
          _ <- events.maintainPartitions(from = today.minusDays(2), days = 3, keepFrom = today.minusDays(30))
          _ <- f.clearStats
          _ <- f.stats("tenant-a", "4915", since.plusSeconds(600), 30)
          _ <- f.stats("tenant-a", "4916", since.plusSeconds(660), 70)
          _ <- events.insertBatch(List.fill(5)(smsRequest(Some("491512"))))
          preview <- repository.preview(AntifraudValueType.prefix, "4915", a, since)
        yield assertTrue(preview == ListPreview(matched = Some(30), total = 100))
      },
      test("a preview of a longer prefix counts only the requests the rollup counts, of its tenant and window") {
        val a = Some(TenantId("tenant-a"))
        // A narrower window than the runner clears, so that the event before it is cleared too.
        val since = at.minusSeconds(600)
        for
          today = at.atZone(ZoneOffset.UTC).toLocalDate
          _ <- events.maintainPartitions(from = today.minusDays(2), days = 3, keepFrom = today.minusDays(30))
          _ <- events.insertBatch(
            List(
              // counted: a request, whatever came of it
              smsRequest(Some("491512")),
              smsRequest(Some("491512"), outcome = AntifraudOutcome.suppressed),
              smsRequest(Some("333333"), outcome = AntifraudOutcome.throttled),
              // not requests: the code that follows a challenge is the request, and a verification is the step after
              smsRequest(Some("491512"), outcome = AntifraudOutcome.challenged),
              smsRequest(Some("491512"), outcome = AntifraudOutcome.challenge_passed),
              smsRequest(Some("491512"), outcome = AntifraudOutcome.challenge_failed),
              smsRequest(Some("491512"), action = AntifraudAction.otp_verify, outcome = AntifraudOutcome.verified),
              // not what the statistics are made of: another channel, no destination
              smsRequest(Some("491512"), channel = AntifraudChannel.email),
              smsRequest(None),
              // not of the entry's tenant, nor of the window
              smsRequest(Some("491512"), tenant = Some(TenantId("tenant-b"))),
              smsRequest(Some("491512"), when = since.minusSeconds(60)),
            ),
          )
          preview <- repository.preview(AntifraudValueType.prefix, "491512", a, since)
        yield assertTrue(preview == ListPreview(matched = Some(2), total = 3))
      },
      test("an entry added again is audited as an update, with what it was; the first time as a create") {
        for
          first <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(3600))), "alice", at)
          again <- repository.upsert(
            entry().copy(expiresAt = Some(at.plusSeconds(60)), reason = Some("changed"), source = "detector:prefix_anomaly", shadow = true),
            "bob",
            at.plusSeconds(1),
          )
          audit <- repository.audit(Some(first.id), 10, None)
        yield assertTrue(
          again.id == first.id,
          audit.map(_.action) == List(AntifraudListAction.update, AntifraudListAction.create),
          // The record has the entry as it became, and what it had.
          audit.head.expiresAt.contains(at.plusSeconds(60)),
          audit.head.reason.contains("changed"),
          audit.head.previous.contains(
            PreviousListValues(expiresAt = Some(at.plusSeconds(3600)), reason = Some("seen in an attack"), source = "manual", shadow = false),
          ),
          audit.last.previous.isEmpty,
        )
      },
      test("pruning the audit also removes counter resets older than the limit, except the newest, which the generations count from") {
        val oldAt = at.minusSeconds(400L * 86400)
        for
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", oldAt)
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", oldAt)
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", at)
          _ <- repository.pruneAudit(at.minusSeconds(365L * 86400), 100)
          left <- resets.since(0, 100).map(_.map(_.generation))
          // Everything is old, and the newest still stays.
          _ <- repository.pruneAudit(at.plusSeconds(400L * 86400), 100)
          newest <- resets.since(0, 100).map(_.map(_.generation))
          latest <- resets.latest
        yield assertTrue(left == List(3L), newest == List(3L), latest == 3L)
      },
    )

object AntifraudListRepositorySpec:
  case class Env(
      repository: AntifraudListRepository,
      events: AntifraudEventRepository,
      resets: AntifraudCounterResetRepository,
      fixtures: Fixtures,
  )

  /** What a case writes that the repository under test does not: the rollup's statistics, for the preview of a prefix. */
  trait Fixtures:
    /** One row of the rollup, `requests` in the minute. */
    def stats(tenant: String, prefix: String, minute: Instant, requests: Int): Task[Unit]

    def clearStats: Task[Unit]

  val at: Instant = Instant.parse("2026-10-23T10:00:00Z")

  /** An SMS code request of tenant A a minute before `at`, unless it says otherwise: the kind of event the rollup makes `requests` of. */
  def smsRequest(
      destPrefix: Option[String],
      tenant: Option[TenantId] = Some(TenantId("tenant-a")),
      when: Instant = at.minusSeconds(60),
      action: AntifraudAction = AntifraudAction.otp_request,
      outcome: AntifraudOutcome = AntifraudOutcome.sent,
      channel: AntifraudChannel = AntifraudChannel.sms,
  ): AntifraudEvent = AntifraudEvent(
    id = UUID.randomUUID(),
    at = when,
    tenantId = tenant,
    clientId = ClientId("c"),
    conversationId = UUID.randomUUID(),
    action = action,
    outcome = outcome,
    channel = channel,
    subjectHash = "h",
    destPrefix = destPrefix,
    userAgent = None,
    decision = None,
    score = None,
    rules = Nil,
    wouldAction = None,
    asn = None,
    asnClass = None,
    country = None,
  )

  def entry(
      listType: AntifraudListType = AntifraudListType.deny,
      valueType: AntifraudValueType = AntifraudValueType.device_id,
      value: String = "dev-1",
      tenantId: Option[TenantId] = None,
      expiresAt: Option[Instant] = None,
      shadow: Boolean = false,
  ): AntifraudListEntry = AntifraudListEntry(
    id = UUID.randomUUID(),
    listType = listType,
    valueType = valueType,
    value = value,
    tenantId = tenantId,
    expiresAt = expiresAt,
    reason = Some("seen in an attack"),
    source = "manual",
    shadow = shadow,
    createdBy = "alice",
    createdAt = at,
    hitCount = 0,
    lastHitAt = None,
  )
