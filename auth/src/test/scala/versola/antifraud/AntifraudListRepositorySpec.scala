package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.{Instant, ZoneOffset}
import java.util.UUID

/** What every [[AntifraudListRepository]] promises: central's entries copied under central's version, written once per version, with
  * the hit counters of an entry that stays kept and those of one that goes dropped; an entry of a kind this version does not know
  * stored and skipped when the cache loads; the hits read back; and a preview against the event log and its rollup. A preview reads
  * the events the real [[AntifraudEventRepository]] wrote and the statistics a case gets from [[AntifraudListRepositorySpec.Fixtures]],
  * which the storage's runner implements; the storage adds in `implementationCases` what it alone promises (a statement timeout). */
trait AntifraudListRepositorySpec extends DatabaseSpecBase[AntifraudListRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudListRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    val events = env.events
    val resets = env.resets
    val f = env.fixtures
    List(
      test("the copy is at version 0 and has nothing before the first replacement") {
        for
          version <- repository.version
          active <- repository.loadActive(at)
        yield assertTrue(version == 0L, active.isEmpty)
      },
      test("replaces the copy with the entries, with every column, and sets the version") {
        for
          e <- ZIO.succeed(
            entry(
              listType = "fake",
              valueType = "cidr",
              value = "10.0.0.0/8",
              tenantId = Some("tenant-a"),
              expiresAt = Some(at.plusSeconds(3600)),
              shadow = true,
            ),
          )
          wrote <- repository.replaceAll(List(e), 4)
          version <- repository.version
          active <- repository.loadActive(at)
        yield assertTrue(
          wrote,
          version == 4L,
          active == List(
            ListedValue(
              id = e.id,
              listType = AntifraudListType.fake,
              valueType = AntifraudValueType.cidr,
              value = "10.0.0.0/8",
              tenantId = Some(TenantId("tenant-a")),
              expiresAt = Some(at.plusSeconds(3600)),
              shadow = true,
            ),
          ),
        )
      },
      test("writes nothing when the copy is at that version already, and says so") {
        val kept = entry(value = "kept")
        for
          first <- repository.replaceAll(List(kept), 2)
          again <- repository.replaceAll(Nil, 2)
          active <- repository.loadActive(at)
          version <- repository.version
        yield assertTrue(first, !again, active.map(_.id) == List(kept.id), version == 2L)
      },
      test("a new version replaces the entries: the ones central no longer has are removed, a changed one is updated") {
        val staying = entry(value = "staying")
        val going = entry(value = "going")
        val added = entry(value = "added", listType = "watch")
        for
          _ <- repository.replaceAll(List(staying, going), 1)
          wrote <- repository.replaceAll(List(staying.copy(reason = Some("changed"), expiresAt = Some(at.plusSeconds(60))), added), 2)
          active <- repository.loadActive(at)
          version <- repository.version
        yield assertTrue(
          wrote,
          version == 2L,
          active.map(_.id).toSet == Set(staying.id, added.id),
          active.find(_.id == staying.id).flatMap(_.expiresAt).contains(at.plusSeconds(60)),
          active.find(_.id == added.id).map(_.listType).contains(AntifraudListType.watch),
        )
      },
      test("an empty list from central empties the copy") {
        for
          _ <- repository.replaceAll(List(entry(), entry(value = "dev-2")), 1)
          wrote <- repository.replaceAll(Nil, 2)
          active <- repository.loadActive(at)
        yield assertTrue(wrote, active.isEmpty)
      },
      test("an entry that stays keeps its hit counters, and one that goes loses them") {
        val staying = entry(value = "staying")
        val going = entry(value = "going")
        for
          _ <- repository.replaceAll(List(staying, going), 1)
          _ <- repository.addHits(List(ListHit(staying.id, 3, at.plusSeconds(10)), ListHit(going.id, 1, at)))
          _ <- repository.replaceAll(List(staying.copy(reason = Some("changed"))), 2)
          kept <- repository.hits(List(staying.id, going.id))
        yield assertTrue(kept == List(ListHit(staying.id, 3, at.plusSeconds(10))))
      },
      test("loads what has not expired, as the cache needs it") {
        val forever = entry(value = "forever")
        val later = entry(value = "later", expiresAt = Some(at.plusSeconds(10)))
        for
          _ <- repository.replaceAll(
            List(
              forever,
              later,
              entry(value = "gone", expiresAt = Some(at)),
              entry(value = "past", expiresAt = Some(at.minusSeconds(10))),
            ),
            1,
          )
          active <- repository.loadActive(at)
        yield assertTrue(
          active.map(_.id).toSet == Set(forever.id, later.id),
          active.find(_.id == later.id).flatMap(_.expiresAt).contains(at.plusSeconds(10)),
        )
      },
      test("an entry of a kind this version does not know is stored, and skipped when the cache loads") {
        for
          now <- Clock.instant
          _ <- repository.replaceAll(
            List(
              entry(listType = "ban", valueType = "ip", value = "x"),
              entry(listType = "deny", valueType = "fingerprint", value = "y"),
              entry(listType = "deny", valueType = "ip", value = "203.0.113.7"),
            ),
            1,
          )
          loaded <- repository.loadActive(now)
        yield assertTrue(loaded.map(_.value) == List("203.0.113.7"))
      },
      test("an entry of an unknown kind keeps its hit counters across versions like any other") {
        val future = entry(listType = "ban", valueType = "ip", value = "x")
        for
          _ <- repository.replaceAll(List(future), 1)
          _ <- repository.addHits(List(ListHit(future.id, 2, at)))
          _ <- repository.replaceAll(List(future), 2)
          counted <- repository.hits(List(future.id))
        yield assertTrue(counted == List(ListHit(future.id, 2, at)))
      },
      test("adds the hits to the counters, and keeps the latest time") {
        val e = entry()
        for
          _ <- repository.replaceAll(List(e), 1)
          _ <- repository.addHits(List(ListHit(e.id, 3, at.plusSeconds(10)), ListHit(UUID.randomUUID(), 5, at)))
          _ <- repository.addHits(List(ListHit(e.id, 2, at.plusSeconds(5))))
          counted <- repository.hits(List(e.id))
        yield assertTrue(counted == List(ListHit(e.id, 5, at.plusSeconds(10))))
      },
      test("hits leaves out an entry that never matched and an id the copy does not have") {
        val hit = entry(value = "hit")
        val quiet = entry(value = "quiet")
        for
          _ <- repository.replaceAll(List(hit, quiet), 1)
          _ <- repository.addHits(List(ListHit(hit.id, 1, at)))
          counted <- repository.hits(List(hit.id, quiet.id, UUID.randomUUID()))
          none <- repository.hits(Nil)
        yield assertTrue(counted == List(ListHit(hit.id, 1, at)), none.isEmpty)
      },
      test("counting hits does not move the version") {
        val e = entry()
        for
          _ <- repository.replaceAll(List(e), 1)
          before <- repository.version
          _ <- repository.addHits(List(ListHit(e.id, 1, at)))
          after <- repository.version
        yield assertTrue(before == 1L, after == before)
      },
      test("keeps the shadow flag, and loads it for the cache") {
        val e = entry(shadow = true)
        for
          _ <- repository.replaceAll(List(e), 1)
          loaded <- repository.loadActive(at)
          _ <- repository.replaceAll(List(e.copy(shadow = false)), 2)
          changed <- repository.loadActive(at)
        yield assertTrue(loaded.map(_.shadow) == List(true), changed.map(_.shadow) == List(false))
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
      test("pruning counter resets removes those older than the limit, except the newest, which the generations count from") {
        val oldAt = at.minusSeconds(400L * 86400)
        for
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", oldAt)
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", oldAt)
          _ <- resets.record(AntifraudCounterResetRepositorySpec.whole, "x", at)
          removed <- repository.pruneCounterResets(at.minusSeconds(365L * 86400), 100)
          left <- resets.since(0, 100).map(_.map(_.generation))
          // Everything is old, and the newest still stays.
          _ <- repository.pruneCounterResets(at.plusSeconds(400L * 86400), 100)
          newest <- resets.since(0, 100).map(_.map(_.generation))
          latest <- resets.latest
        yield assertTrue(removed == 2, left == List(3L), newest == List(3L), latest == 3L)
      },
      test("pruning counter resets removes at most the limit, oldest first") {
        val oldAt = at.minusSeconds(400L * 86400)
        for
          _ <- ZIO.foreachDiscard(1 to 5)(_ => resets.record(AntifraudCounterResetRepositorySpec.whole, "x", oldAt))
          first <- repository.pruneCounterResets(at, 2)
          left <- resets.since(0, 100).map(_.map(_.generation))
        yield assertTrue(first == 2, left == List(3L, 4L, 5L))
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

  /** The kinds are text, as central sends them: a case names one this version does not know the same way. */
  def entry(
      listType: String = "deny",
      valueType: String = "device_id",
      value: String = "dev-1",
      tenantId: Option[String] = None,
      expiresAt: Option[Instant] = None,
      shadow: Boolean = false,
  ): PolicyListEntry = PolicyListEntry(
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
  )
