package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudListsSpec extends UnitSpecBase:

  private val tenant = TenantId("tenant-a")
  private val operator = "op"

  /** The repository, in memory. */
  private class FakeRepository(var entries: List[AntifraudListEntry] = Nil) extends AntifraudListRepository:
    var failLoad = false
    var ver = 0L
    var loads = 0
    def changed(): Unit = ver += 1
    var failHits = false
    var written: List[ListHit] = Nil
    var audits: List[AntifraudListAudit] = Nil

    override def upsert(entry: AntifraudListEntry, actor: String, at: Instant): Task[AntifraudListEntry] =
      ZIO.succeed:
        val same =
          entries.find(e => e.listType == entry.listType && e.valueType == entry.valueType && e.value == entry.value && e.tenantId == entry.tenantId)
        val stored = same.fold(entry)(e => e.copy(expiresAt = entry.expiresAt, reason = entry.reason, source = entry.source))
        entries = stored :: entries.filterNot(e => same.exists(_.id == e.id))
        changed()
        stored

    override def delete(id: UUID, actor: String, at: Instant): Task[Boolean] =
      ZIO.succeed:
        val found = entries.exists(_.id == id)
        entries = entries.filterNot(_.id == id)
        changed()
        found

    override def find(id: UUID): Task[Option[AntifraudListEntry]] = ZIO.succeed(entries.find(_.id == id))

    var previewResult = ListPreview(matched = None, total = 0)

    override def preview(valueType: AntifraudValueType, value: String, since: Instant): Task[ListPreview] = ZIO.succeed(previewResult)

    override def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]] = ZIO.succeed(entries)

    override def version: Task[Long] = if failLoad then ZIO.fail(RuntimeException("db down")) else ZIO.succeed(ver)

    override def purgeExpired(now: Instant, limit: Int): Task[Int] = ZIO.succeed(0)

    override def pruneAudit(before: Instant, limit: Int): Task[Int] = ZIO.succeed(0)

    override def loadActive(now: Instant): Task[List[ListedValue]] =
      loads += 1
      if failLoad then ZIO.fail(RuntimeException("db down"))
      else
        ZIO.succeed:
          entries.filter(_.expiresAt.forall(_.isAfter(now))).map: e =>
            ListedValue(e.id, e.listType, e.valueType, e.value, e.tenantId, e.expiresAt, e.shadow)

    override def addHits(hits: List[ListHit]): Task[Unit] =
      if failHits then ZIO.fail(RuntimeException("db down")) else ZIO.succeed { written = written ++ hits }

    override def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]] = ZIO.succeed(audits)

  private val settings = AntifraudLists.Settings.default.copy(enabled = true)

  // delayAt 1 so that a watch (20) is visible; challenge 50, block 100
  private val engine = Antifraud.Settings(enabled = true, delayAt = 1, challengeAt = 50, blockAt = 100)

  private def ctx(
      ip: Option[String] = None,
      deviceId: Option[String] = None,
      destPrefix: Option[String] = None,
      tenantId: Option[TenantId] = Some(tenant),
  ) = AntifraudContext(
    tenantId = tenantId,
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = None,
    destPrefix = destPrefix,
    deviceId = deviceId,
    ip = ip,
    asn = None,
    userAgent = None,
    ja4 = None,
    signals = Map.empty,
  )

  private def make(repository: FakeRepository, config: AntifraudLists.Settings = settings) =
    val cache = AntifraudLists.ListCache()
    (AntifraudLists.Impl(repository, config, cache), cache)

  private def add(
      lists: AntifraudLists,
      listType: AntifraudListType,
      valueType: AntifraudValueType,
      value: String,
      tenantId: Option[TenantId] = None,
      shadow: Boolean = false,
  ) =
    lists.create(
      NewListEntry(
        listType = listType,
        valueType = valueType,
        value = value,
        tenantId = tenantId,
        expiresAt = Some(Instant.EPOCH.plusSeconds(365 * 86400)),
        reason = None,
        source = "manual",
        shadow = shadow,
      ),
      operator,
    )

  /** The weight-only rule of a source that is not the lists, here a score of 60 (a challenge). */
  private val scoring = new Antifraud.RuleSet:
    def rules = ZIO.succeed(List(AntifraudRule("velocity", 60, None, _ => true)))

  private val blocking = new Antifraud.RuleSet:
    def rules = ZIO.succeed(List(AntifraudRule("range_closed", 0, Some(DecisionAction.Block), _ => true)))

  private def decide(lists: AntifraudLists, others: Antifraud.RuleSet*)(c: AntifraudContext) =
    Antifraud.Impl(engine, lists.ruleSet :: others.toList).evaluate(c)

  def spec = suite("AntifraudLists")(
    test("contributes no rule while off, whatever the table holds") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      val (off, _) = make(repository, AntifraudLists.Settings.default)
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d")
        offRules <- off.ruleSet.rules
        d <- decide(off)(ctx(deviceId = Some("d")))
      yield assertTrue(offRules.isEmpty, d == Decision.allow)
    },
    test("a deny blocks, a fake fakes, a watch adds its weight") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "bad")
        _ <- add(lists, AntifraudListType.fake, AntifraudValueType.prefix, "4915")
        _ <- add(lists, AntifraudListType.watch, AntifraudValueType.cidr, "10.0.0.0/8")
        blocked <- decide(lists)(ctx(deviceId = Some("bad")))
        faked <- decide(lists)(ctx(destPrefix = Some("491512")))
        watched <- decide(lists)(ctx(ip = Some("10.2.3.4")))
        clean <- decide(lists)(ctx(ip = Some("192.0.2.1"), deviceId = Some("fine")))
      yield assertTrue(
        blocked == Decision(DecisionAction.Block, 0, List("deny_list:device_id")),
        faked == Decision(DecisionAction.Fake, 0, List("fake_list:prefix")),
        watched == Decision(DecisionAction.Delay, 20, List("watch_list:cidr")),
        clean == Decision.allow,
      )
    },
    test("an allow outweighs score-based rules, the lists' own watch included") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.allow, AntifraudValueType.device_id, "vip")
        _ <- add(lists, AntifraudListType.watch, AntifraudValueType.device_id, "vip")
        unlisted <- decide(lists, scoring)(ctx(deviceId = Some("other")))
        allowed <- decide(lists, scoring)(ctx(deviceId = Some("vip")))
      yield assertTrue(
        unlisted.action == DecisionAction.Challenge,
        allowed.action == DecisionAction.Allow,
        allowed.firedRules.contains("allow_list:device_id"),
      )
    },
    test("an allow does not override a rule of another source that forces an action") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.allow, AntifraudValueType.device_id, "vip")
        d <- decide(lists, scoring, blocking)(ctx(deviceId = Some("vip")))
      yield assertTrue(d.action == DecisionAction.Block)
    },
    test("an allow never overrides a deny of the same specificity, nor a more specific one") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.allow, AntifraudValueType.device_id, "same")
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "same")
        _ <- add(lists, AntifraudListType.allow, AntifraudValueType.cidr, "203.0.113.9/32")
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.ip, "203.0.113.9")
        tie <- decide(lists, scoring)(ctx(deviceId = Some("same")))
        narrower <- decide(lists, scoring)(ctx(ip = Some("203.0.113.9")))
        rest <- decide(lists, scoring)(ctx(ip = Some("203.0.113.1")))
      yield assertTrue(
        tie.action == DecisionAction.Block,
        narrower.action == DecisionAction.Block,
        // nothing is listed for it, so only the score applies
        rest.action == DecisionAction.Challenge,
      )
    },
    test("an allow that is more specific than a deny wins over it") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.cidr, "203.0.113.0/24")
        _ <- add(lists, AntifraudListType.allow, AntifraudValueType.ip, "203.0.113.9")
        allowed <- decide(lists, scoring)(ctx(ip = Some("203.0.113.9")))
        denied <- decide(lists, scoring)(ctx(ip = Some("203.0.113.10")))
      yield assertTrue(allowed.action == DecisionAction.Allow, denied.action == DecisionAction.Block)
    },
    test("a tenant's entry does not apply to another tenant") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d", tenantId = Some(tenant))
        mine <- decide(lists)(ctx(deviceId = Some("d")))
        other <- decide(lists)(ctx(deviceId = Some("d"), tenantId = Some(TenantId("tenant-b"))))
      yield assertTrue(mine.action == DecisionAction.Block, other == Decision.allow)
    },
    test("an entry stops applying when it expires, without a refresh") {
      val (lists, _) = make(FakeRepository())
      for
        now <- Clock.instant
        _ <- lists.create(
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.device_id,
            value = "d",
            tenantId = None,
            expiresAt = Some(now.plusSeconds(60)),
            reason = None,
            source = "manual",
            shadow = false,
          ),
          operator,
        )
        before <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- TestClock.adjust(61.seconds)
        after <- decide(lists)(ctx(deviceId = Some("d")))
      yield assertTrue(before.action == DecisionAction.Block, after == Decision.allow)
    },
    test("a refresh that fails keeps the lists there are; the next one that works replaces them") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d")
        _ <- ZIO.succeed { repository.failLoad = true }
        _ <- lists.refresh
        kept <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- ZIO.succeed { repository.failLoad = false; repository.entries = Nil; repository.changed() }
        _ <- lists.refresh
        gone <- decide(lists)(ctx(deviceId = Some("d")))
      yield assertTrue(kept.action == DecisionAction.Block, gone == Decision.allow)
    },
    test("starting reads the table, a change made elsewhere shows within the refresh interval, and the hits are written") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      ZIO.scoped:
        for
          _ <- lists.start
          _ <- ZIO.succeed {
            repository.changed()
            repository.entries = List(
              AntifraudListEntry(
                id = UUID.randomUUID(),
                listType = AntifraudListType.deny,
                valueType = AntifraudValueType.device_id,
                value = "d",
                tenantId = None,
                expiresAt = None,
                reason = None,
                source = "manual",
                shadow = false,
                createdBy = "t",
                createdAt = Instant.EPOCH,
                hitCount = 0,
                lastHitAt = None,
              ),
            )
          }
          before <- decide(lists)(ctx(deviceId = Some("d")))
          _ <- TestClock.adjust(settings.refreshInterval) *> ZIO.yieldNow.repeatN(20)
          after <- decide(lists)(ctx(deviceId = Some("d")))
          _ <- decide(lists)(ctx(deviceId = Some("d")))
          _ <- TestClock.adjust(settings.hitFlushInterval) *> ZIO.yieldNow.repeatN(20)
          written <- ZIO.succeed(repository.written)
        yield assertTrue(before == Decision.allow, after.action == DecisionAction.Block, written.map(_.count) == List(2L))
    },
    test("counts a hit per entry that decided, in memory, and flushes them in one batch; a failed flush keeps them") {
      val repository = FakeRepository()
      val (lists, cache) = make(repository)
      for
        stored <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d")
        _ <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- decide(lists)(ctx(deviceId = Some("other")))
        _ <- ZIO.succeed { repository.failHits = true }
        _ <- lists.flushHits
        _ <- ZIO.succeed { repository.failHits = false }
        _ <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- lists.flushHits
        _ <- lists.flushHits
      yield assertTrue(
        repository.written.map(h => (h.id, h.count)) == List((stored.id, 3L)),
        cache.drain().isEmpty,
      )
    },
    test("refuses a value that is not valid for its type, and an expiry in the past") {
      val (lists, _) = make(FakeRepository())
      for
        bad <- add(lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.0.0.0/40").either
        past <- lists.create(
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.device_id,
            value = "d",
            tenantId = None,
            expiresAt = Some(Instant.EPOCH),
            reason = None,
            source = "manual",
            shadow = false,
          ),
          operator,
        ).either
      yield assertTrue(bad.left.exists(_.isInstanceOf[InvalidListEntry]), past.left.exists(_.isInstanceOf[InvalidListEntry]))
    },
    test("adding a value again refreshes it, and deleting it lifts the effect at once") {
      val (lists, _) = make(FakeRepository())
      for
        first <- add(lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.9.9.9/16")
        second <- add(lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.9.0.0/16")
        blocked <- decide(lists)(ctx(ip = Some("10.9.1.1")))
        removed <- lists.delete(first.id, operator)
        removedAgain <- lists.delete(first.id, operator)
        after <- decide(lists)(ctx(ip = Some("10.9.1.1")))
      yield assertTrue(
        first.value == "10.9.0.0/16",
        second.id == first.id,
        blocked.action == DecisionAction.Block,
        removed,
        !removedAgain,
        after == Decision.allow,
      )
    },
    test("an allow is refused for anything but an exact subject, and cannot exempt a population") {
      val (lists, _) = make(FakeRepository())
      def allow(valueType: AntifraudValueType, value: String) = add(lists, AntifraudListType.allow, valueType, value).either.map(_.isLeft)
      for
        asn <- allow(AntifraudValueType.asn, "64500")
        wide <- allow(AntifraudValueType.cidr, "10.0.0.0/8")
        prefix <- allow(AntifraudValueType.prefix, "49")
        domain <- allow(AntifraudValueType.email_domain, "gmail.com")
        ua <- allow(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("x"))
        ja4 <- allow(AntifraudValueType.ja4_hash, "t13d")
        host <- allow(AntifraudValueType.cidr, "10.0.0.1/32")
        host6 <- allow(AntifraudValueType.cidr, "2001:db8::1/128")
        ip <- allow(AntifraudValueType.ip, "10.0.0.2")
        device <- allow(AntifraudValueType.device_id, "d")
        phone <- allow(AntifraudValueType.phone_hash, "h")
        // the same broad values are fine for a deny
        denied <- add(lists, AntifraudListType.deny, AntifraudValueType.asn, "64500").either.map(_.isRight)
      yield assertTrue(asn, wide, prefix, domain, ua, ja4, !host, !host6, !ip, !device, !phone, denied)
    },
    test("an entry that names many clients, or an automated one, needs an expiry") {
      val (lists, _) = make(FakeRepository())
      def create(valueType: AntifraudValueType, value: String, source: String, expiresAt: Option[Instant]) =
        lists
          .create(
            NewListEntry(
              listType = AntifraudListType.deny,
              valueType = valueType,
              value = value,
              tenantId = None,
              expiresAt = expiresAt,
              reason = None,
              source = source,
              shadow = false,
            ),
            operator,
          )
          .either
          .map(_.isRight)
      val later = Some(Instant.EPOCH.plusSeconds(3600))
      for
        broad <- create(AntifraudValueType.asn, "64500", "manual", None)
        wide <- create(AntifraudValueType.cidr, "10.0.0.0/16", "manual", None)
        prefix <- create(AntifraudValueType.prefix, "4915", "manual", None)
        narrow <- create(AntifraudValueType.cidr, "10.1.0.0/24", "manual", None)
        exact <- create(AntifraudValueType.device_id, "d", "manual", None)
        automated <- create(AntifraudValueType.device_id, "d2", "rule:velocity", None)
        automatedExpiring <- create(AntifraudValueType.device_id, "d3", "rule:velocity", later)
        broadExpiring <- create(AntifraudValueType.asn, "64501", "manual", later)
      yield assertTrue(!broad, !wide, !prefix, narrow, exact, !automated, automatedExpiring, broadExpiring)
    },
    test("a shadow entry enforces nothing and only shows in the fired rules") {
      val (lists, _) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d", shadow = true)
        d <- decide(lists)(ctx(deviceId = Some("d")))
      yield assertTrue(d == Decision(DecisionAction.Allow, 0, List("shadow_list:device_id")))
    },
    test("an enforcing entry that would have matched too much of the recent traffic is refused, a shadow one is not") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = Some(600), total = 1000) }
        enforcing <- add(lists, AntifraudListType.deny, AntifraudValueType.prefix, "49").either
        shadow <- add(lists, AntifraudListType.deny, AntifraudValueType.prefix, "49", shadow = true).either
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = Some(3), total = 1000) }
        small <- add(lists, AntifraudListType.deny, AntifraudValueType.prefix, "4915").either
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = Some(5), total = 5) }
        tooFew <- add(lists, AntifraudListType.deny, AntifraudValueType.prefix, "4916").either
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = None, total = 1000) }
        unknown <- add(lists, AntifraudListType.deny, AntifraudValueType.asn, "64500").either
        previewed <- lists.preview(
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.prefix,
            value = "49",
            tenantId = None,
            expiresAt = Some(Instant.EPOCH.plusSeconds(60)),
            reason = None,
            source = "manual",
            shadow = false,
          ),
        )
      yield assertTrue(
        enforcing.left.exists(_.isInstanceOf[InvalidListEntry]),
        shadow.isRight,
        small.isRight,
        tooFew.isRight,
        unknown.isRight,
        previewed == ListPreview(matched = None, total = 1000),
      )
    },
    test("a page outside 1 to 500 entries, or before the first, is refused") {
      val (lists, _) = make(FakeRepository())
      for
        ok <- lists.list(AntifraudListFilter.all, 500, 0).either
        zero <- lists.list(AntifraudListFilter.all, 0, 0).either
        huge <- lists.list(AntifraudListFilter.all, 501, 0).either
        negative <- lists.list(AntifraudListFilter.all, 10, -1).either
        auditHuge <- lists.audit(None, 501).either
      yield assertTrue(
        ok.isRight,
        List(zero, huge, negative, auditHuge).forall(_.left.exists(_.isInstanceOf[InvalidListEntry])),
      )
    },
    test("a first load that fails is retried every second, not at the steady interval, and the lists apply once it works") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      ZIO.scoped:
        for
          _ <- ZIO.succeed {
            repository.failLoad = true
            repository.changed()
            repository.entries = List(
              AntifraudListEntry(
                id = UUID.randomUUID(),
                listType = AntifraudListType.deny,
                valueType = AntifraudValueType.device_id,
                value = "d",
                tenantId = None,
                expiresAt = None,
                reason = None,
                source = "manual",
                shadow = false,
                createdBy = "t",
                createdAt = Instant.EPOCH,
                hitCount = 0,
                lastHitAt = None,
              ),
            )
          }
          fiber <- lists.start.fork
          _ <- TestClock.adjust(2.seconds) *> ZIO.yieldNow.repeatN(20)
          stillStarting <- fiber.poll.map(_.isEmpty)
          _ <- ZIO.succeed { repository.failLoad = false }
          _ <- TestClock.adjust(1.second) *> ZIO.yieldNow.repeatN(20)
          _ <- fiber.join
          d <- decide(lists)(ctx(deviceId = Some("d")))
        yield assertTrue(stillStarting, d.action == DecisionAction.Block)
    },
    test("an unchanged table is not read again, except once a minute for a change made behind the service's back") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(lists, AntifraudListType.deny, AntifraudValueType.device_id, "d")
        afterCreate <- ZIO.succeed(repository.loads)
        _ <- lists.refresh.repeatN(9)
        unchanged <- ZIO.succeed(repository.loads)
        // by hand in SQL: no version bump
        _ <- ZIO.succeed { repository.entries = Nil }
        _ <- lists.refresh
        stillThere <- decide(lists)(ctx(deviceId = Some("d")))
        _ <- TestClock.adjust(61.seconds)
        _ <- lists.refresh
        gone <- decide(lists)(ctx(deviceId = Some("d")))
      yield assertTrue(unchanged == afterCreate, stillThere.action == DecisionAction.Block, gone == Decision.allow)
    },
    test("counts hits by list and kind of value, for the metric") {
      val (lists, cache) = make(FakeRepository())
      for
        _ <- add(lists, AntifraudListType.fake, AntifraudValueType.prefix, "4915")
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
      yield assertTrue(cache.drainKinds() == List((AntifraudListType.fake, AntifraudValueType.prefix) -> 2L), cache.drainKinds().isEmpty)
    },
  )
