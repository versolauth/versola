package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID
import scala.util.Try

object AntifraudListsSpec extends UnitSpecBase:

  private val tenant = TenantId("tenant-a")

  /** The copy of central's lists, in memory. */
  private class FakeRepository(var entries: List[PolicyListEntry] = Nil) extends AntifraudListRepository:
    var failLoad = false
    var ver = 0L
    var loads = 0
    def changed(): Unit = ver += 1
    var failHits = false
    var written: List[ListHit] = Nil
    var hitsAsked: List[List[UUID]] = Nil

    override def replaceAll(entries: List[PolicyListEntry], version: Long, at: Instant, notBefore: Instant): Task[Boolean] =
      ZIO.succeed:
        if ver == version then false
        else
          this.entries = entries
          ver = version
          true

    override def applyChanges(entries: List[PolicyListEntry], removed: List[UUID], from: Long, to: Long): Task[Boolean] =
      ZIO.succeed:
        if ver != from then false
        else
          this.entries = this.entries.filterNot(e => removed.contains(e.id) || entries.exists(_.id == e.id)) ++ entries
          ver = to
          true

    override def copied: Task[AntifraudListRepository.Copy] = ZIO.succeed(AntifraudListRepository.Copy(ver, None))

    var expiredBefore: List[(Instant, Int)] = Nil

    override def removeExpired(before: Instant, limit: Int): Task[Int] =
      ZIO.succeed:
        expiredBefore = expiredBefore :+ (before -> limit)
        0

    var previewResult = ListPreview(matched = None, total = 0)
    var previewedFor: List[Option[TenantId]] = Nil

    /** Answers by tenant, as the repository does; by default `previewResult` for all. */
    var previewBy: Option[Option[TenantId] => ListPreview] = None
    var previewedValues: List[String] = Nil
    var previewedSince: List[Instant] = Nil

    override def preview(valueType: AntifraudValueType, value: String, tenantId: Option[TenantId], since: Instant): Task[ListPreview] =
      ZIO.succeed:
        previewedFor = previewedFor :+ tenantId
        previewedValues = previewedValues :+ value
        previewedSince = previewedSince :+ since
        previewBy.fold(previewResult)(_(tenantId))

    override def version: Task[Long] = if failLoad then ZIO.fail(RuntimeException("db down")) else ZIO.succeed(ver)

    override def pruneCounterResets(before: Instant, limit: Int): Task[Int] = ZIO.succeed(0)

    override def loadActive(now: Instant): Task[List[ListedValue]] =
      loads += 1
      if failLoad then ZIO.fail(RuntimeException("db down"))
      else
        ZIO.succeed:
          entries.filter(_.expiresAt.forall(_.isAfter(now))).flatMap: e =>
            for
              listType <- Try(AntifraudListType.valueOf(e.listType)).toOption
              valueType <- Try(AntifraudValueType.valueOf(e.valueType)).toOption
            yield ListedValue(e.id, listType, valueType, e.value, e.tenantId.map(TenantId(_)), e.expiresAt, e.shadow)

    override def addHits(hits: List[ListHit]): Task[Unit] =
      if failHits then ZIO.fail(RuntimeException("db down")) else ZIO.succeed { written = written ++ hits }

    override def hits(ids: List[UUID]): Task[List[ListHit]] =
      ZIO.succeed:
        hitsAsked = hitsAsked :+ ids
        written.filter(h => ids.contains(h.id))

  // The longest an entry may live is longer than the year the helpers below give theirs; the limit has specs of its own.
  private val settings = AntifraudTestSettings.lists.copy(enabled = true, maxBroadTtl = 400.days)

  // challenge 50, block 100: a watch (20) is only visible in the score
  private val engine = AntifraudTestSettings.decision.copy(enabled = true)
  private val thresholds = AntifraudTestSettings.tuning.copy(challengeAt = 50, blockAt = 100)

  private def ctx(
      ip: Option[String] = None,
      subjectHash: Option[String] = None,
      destPrefix: Option[String] = None,
      tenantId: Option[TenantId] = Some(tenant),
      asn: Option[Int] = None,
      signals: Map[String, String] = Map.empty,
  ) = AntifraudContext(
    tenantId = tenantId,
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = subjectHash,
    destPrefix = destPrefix,
    deviceId = None,
    ip = ip,
    asn = asn,
    userAgent = None,
    ja4 = None,
    signals = signals,
  )

  private def make(repository: FakeRepository, config: AntifraudLists.Settings = settings) =
    val cache = AntifraudLists.ListCache()
    (AntifraudLists.Impl(repository, config, AntifraudTestSettings.listsPolicy, cache), cache)

  /** What central does with an entry, and the sync after it: the service says whether it is valid and what its value is, the copy
    * is made what central has and the snapshot is built again. */
  private def put(repository: FakeRepository, lists: AntifraudLists.Impl, entry: NewListEntry): Task[PolicyListEntry] =
    for
      value <- lists.check(entry)
      stored = PolicyListEntry(
        id = UUID.randomUUID(),
        listType = entry.listType.toString,
        valueType = entry.valueType.toString,
        value = value,
        tenantId = entry.tenantId.map(identity[String]),
        expiresAt = entry.expiresAt,
        reason = entry.reason,
        source = entry.source,
        shadow = entry.shadow,
        createdBy = "central",
        createdAt = Instant.EPOCH,
      )
      _ <- repository.replaceAll(repository.entries :+ stored, repository.ver + 1, Instant.EPOCH, Instant.EPOCH)
      _ <- lists.refresh
    yield stored

  private def add(
      repository: FakeRepository,
      lists: AntifraudLists.Impl,
      listType: AntifraudListType,
      valueType: AntifraudValueType,
      value: String,
      tenantId: Option[TenantId] = None,
      shadow: Boolean = false,
  ) =
    put(
      repository,
      lists,
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
    )

  /** The weight-only rule of a source that is not the lists, here a score of 60 (a challenge). */
  private val scoring = new Antifraud.RuleSet:
    def rules = ZIO.succeed(List(AntifraudRule("velocity", 60, None, _ => true)))

  private val blocking = new Antifraud.RuleSet:
    def rules = ZIO.succeed(List(AntifraudRule("range_closed", 0, Some(DecisionAction.Block), _ => true)))

  private def decide(lists: AntifraudLists, others: Antifraud.RuleSet*)(c: AntifraudContext) =
    AntifraudTestSettings.engine(engine, lists.ruleSet :: others.toList, thresholds).evaluate(c)

  def spec = suite("AntifraudLists")(
    // versolauth/versola#1052
    test("the clean-up removes the entries that expired more than a day ago, a batch at a time") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- TestClock.setTime(Instant.parse("2026-11-20T10:00:00Z"))
        _ <- lists.cleanUp
      yield assertTrue(repository.expiredBefore == List((Instant.parse("2026-11-19T10:00:00Z"), 1000)))
    },
    test("contributes no rule while off, whatever the table holds") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      val (off, _) = make(repository, AntifraudTestSettings.lists)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        offRules <- off.ruleSet.rules
        d <- decide(off)(ctx(subjectHash = Some("d")))
      yield assertTrue(offRules.isEmpty, d == Decision.allow)
    },
    test("a deny blocks, a fake fakes, a watch adds its weight") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "bad")
        _ <- add(repository, lists, AntifraudListType.fake, AntifraudValueType.prefix, "4915")
        _ <- add(repository, lists, AntifraudListType.watch, AntifraudValueType.cidr, "10.0.0.0/8")
        blocked <- decide(lists)(ctx(subjectHash = Some("bad")))
        faked <- decide(lists)(ctx(destPrefix = Some("491512")))
        watched <- decide(lists)(ctx(ip = Some("10.2.3.4")))
        clean <- decide(lists)(ctx(ip = Some("192.0.2.1"), subjectHash = Some("fine")))
      yield assertTrue(
        blocked == Decision(DecisionAction.Block, 0, List("deny_list:phone_hash")),
        faked == Decision(DecisionAction.Fake, 0, List("fake_list:prefix")),
        watched == Decision(DecisionAction.Allow, 20, List("watch_list:cidr")),
        clean == Decision.allow,
      )
    },
    test("an allow outweighs score-based rules, the lists' own watch included") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.phone_hash, "vip")
        _ <- add(repository, lists, AntifraudListType.watch, AntifraudValueType.phone_hash, "vip")
        unlisted <- decide(lists, scoring)(ctx(subjectHash = Some("other")))
        allowed <- decide(lists, scoring)(ctx(subjectHash = Some("vip")))
      yield assertTrue(
        unlisted.action == DecisionAction.Challenge,
        allowed.action == DecisionAction.Allow,
        allowed.firedRules.contains("allow_list:phone_hash"),
      )
    },
    test("an allow does not override a rule of another source that forces an action") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.phone_hash, "vip")
        d <- decide(lists, scoring, blocking)(ctx(subjectHash = Some("vip")))
      yield assertTrue(d.action == DecisionAction.Block)
    },
    test("an allow never overrides a deny of the same specificity, nor a more specific one") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.phone_hash, "same")
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "same")
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.cidr, "203.0.113.9/32")
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.ip, "203.0.113.9")
        tie <- decide(lists, scoring)(ctx(subjectHash = Some("same")))
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
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.cidr, "203.0.113.0/24")
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.ip, "203.0.113.9")
        allowed <- decide(lists, scoring)(ctx(ip = Some("203.0.113.9")))
        denied <- decide(lists, scoring)(ctx(ip = Some("203.0.113.10")))
      yield assertTrue(allowed.action == DecisionAction.Allow, denied.action == DecisionAction.Block)
    },
    test("a tenant's entry does not apply to another tenant") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d", tenantId = Some(tenant))
        mine <- decide(lists)(ctx(subjectHash = Some("d")))
        other <- decide(lists)(ctx(subjectHash = Some("d"), tenantId = Some(TenantId("tenant-b"))))
      yield assertTrue(mine.action == DecisionAction.Block, other == Decision.allow)
    },
    test("an entry stops applying when it expires, without a refresh") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        now <- Clock.instant
        _ <- put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.phone_hash,
            value = "d",
            tenantId = None,
            expiresAt = Some(now.plusSeconds(60)),
            reason = None,
            source = "manual",
            shadow = false,
          ),
        )
        before <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- TestClock.adjust(61.seconds)
        after <- decide(lists)(ctx(subjectHash = Some("d")))
      yield assertTrue(before.action == DecisionAction.Block, after == Decision.allow)
    },
    test("a refresh that fails keeps the lists there are; the next one that works replaces them") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        _ <- ZIO.succeed { repository.failLoad = true }
        _ <- lists.refresh
        kept <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- ZIO.succeed { repository.failLoad = false; repository.entries = Nil; repository.changed() }
        _ <- lists.refresh
        gone <- decide(lists)(ctx(subjectHash = Some("d")))
      yield assertTrue(kept.action == DecisionAction.Block, gone == Decision.allow)
    },
    test("the status has the size and age of the snapshot, and the message of the last refresh that failed until one works") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        cold <- lists.status
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        _ <- lists.refresh
        loaded <- lists.status
        _ <- TestClock.adjust(10.seconds)
        _ <- ZIO.succeed { repository.failLoad = true }
        _ <- lists.refresh
        failed <- lists.status
        _ <- ZIO.succeed { repository.failLoad = false; repository.changed() }
        _ <- lists.refresh
        recovered <- lists.status
        off <- make(repository, settings.copy(enabled = false))._1.status
      yield assertTrue(
        cold == ListsStatus(enabled = true, loadedAt = None, size = 0, skipped = 0, lastError = None),
        loaded.enabled,
        loaded.size == 1,
        loaded.loadedAt.isDefined,
        loaded.lastError.isEmpty,
        failed.size == 1,
        failed.loadedAt == loaded.loadedAt,
        failed.lastError.contains("db down"),
        recovered.lastError.isEmpty,
        recovered.loadedAt.exists(at => loaded.loadedAt.exists(at.isAfter)),
        !off.enabled,
      )
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
              PolicyListEntry(
                id = UUID.randomUUID(),
                listType = "deny",
                valueType = "phone_hash",
                value = "d",
                tenantId = None,
                expiresAt = None,
                reason = None,
                source = "manual",
                shadow = false,
                createdBy = "t",
                createdAt = Instant.EPOCH,
              ),
            )
          }
          before <- decide(lists)(ctx(subjectHash = Some("d")))
          _ <- TestClock.adjust(settings.refreshInterval) *> ZIO.yieldNow.repeatN(20)
          after <- decide(lists)(ctx(subjectHash = Some("d")))
          _ <- decide(lists)(ctx(subjectHash = Some("d")))
          _ <- TestClock.adjust(AntifraudTestSettings.listsPolicy.hitFlushInterval) *> ZIO.yieldNow.repeatN(20)
          written <- ZIO.succeed(repository.written)
        yield assertTrue(before == Decision.allow, after.action == DecisionAction.Block, written.map(_.count) == List(2L))
    },
    test("counts a hit per entry that decided, in memory, and flushes them in one batch; a failed flush keeps them") {
      val repository = FakeRepository()
      val (lists, cache) = make(repository)
      for
        stored <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        _ <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- decide(lists)(ctx(subjectHash = Some("other")))
        _ <- ZIO.succeed { repository.failHits = true }
        _ <- lists.flushHits
        _ <- ZIO.succeed { repository.failHits = false }
        _ <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- lists.flushHits
        _ <- lists.flushHits
      yield assertTrue(
        repository.written.map(h => (h.id, h.count)) == List((stored.id, 3L)),
        cache.drain().isEmpty,
      )
    },
    test("refuses a value that is not valid for its type, and an expiry in the past") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        bad <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.0.0.0/40").either
        past <- put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.phone_hash,
            value = "d",
            tenantId = None,
            expiresAt = Some(Instant.EPOCH),
            reason = None,
            source = "manual",
            shadow = false,
          ),
        ).either
      yield assertTrue(bad.left.exists(_.isInstanceOf[InvalidListEntry]), past.left.exists(_.isInstanceOf[InvalidListEntry]))
    },
    test("a copy that central changes is picked up: an entry it removes stops applying at once, one it adds applies") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        first <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.9.9.9/16")
        blocked <- decide(lists)(ctx(ip = Some("10.9.1.1")))
        _ <- repository.replaceAll(Nil, repository.ver + 1, Instant.EPOCH, Instant.EPOCH)
        _ <- lists.refresh
        after <- decide(lists)(ctx(ip = Some("10.9.1.1")))
      yield assertTrue(first.value == "10.9.0.0/16", blocked.action == DecisionAction.Block, after == Decision.allow)
    },
    test("an allow is refused for anything but an exact subject, and cannot exempt a population") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def allow(valueType: AntifraudValueType, value: String) = add(repository, lists, AntifraudListType.allow, valueType, value).either.map(_.isLeft)
      for
        asn <- allow(AntifraudValueType.asn, "64500")
        asnWritten <- allow(AntifraudValueType.asn, "AS64500")
        wide <- allow(AntifraudValueType.cidr, "10.0.0.0/8")
        prefix <- allow(AntifraudValueType.prefix, "49")
        domain <- allow(AntifraudValueType.email_domain, "gmail.com")
        country <- allow(AntifraudValueType.country, "KZ")
        ua <- allow(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("x"))
        ja4 <- allow(AntifraudValueType.ja4_hash, "t13d")
        host <- allow(AntifraudValueType.cidr, "10.0.0.1/32")
        host6 <- allow(AntifraudValueType.cidr, "2001:db8::1/128")
        ip <- allow(AntifraudValueType.ip, "10.0.0.2")
        // a device id is what the client says it is until P1-1 issues it: not a subject an allow may name
        device <- allow(AntifraudValueType.device_id, "d")
        phone <- allow(AntifraudValueType.phone_hash, "h")
        // the same broad values are fine for a deny
        denied <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.cidr, "10.0.0.0/8").either.map(_.isRight)
      yield assertTrue(asn, asnWritten, wide, prefix, domain, country, ua, ja4, !host, !host6, !ip, device, !phone, denied)
    },
    test("an entry of a kind no request carries yet is refused, so that nothing is listed that cannot match") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def deny(valueType: AntifraudValueType, value: String) = add(repository, lists, AntifraudListType.deny, valueType, value).either
      for
        device <- deny(AntifraudValueType.device_id, "d")
        ja4 <- deny(AntifraudValueType.ja4_hash, "t13d")
        phone <- deny(AntifraudValueType.phone_hash, "h")
        domain <- deny(AntifraudValueType.email_domain, "mailinator.com")
      yield assertTrue(
        device.swap.exists(_.getMessage.contains("device_id")),
        ja4.isLeft,
        phone.isRight,
        domain.isRight,
      )
    },
    test("an asn entry is accepted in either spelling, stored as digits, and matches the AS the address was given") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        written <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.asn, "AS64500")
        lower <- add(repository, lists, AntifraudListType.watch, AntifraudValueType.asn, "as64501")
        blocked <- decide(lists)(ctx(asn = Some(64500)))
        watched <- decide(lists)(ctx(asn = Some(64501)))
        other <- decide(lists)(ctx(asn = Some(64502)))
        none <- decide(lists)(ctx(asn = None))
      yield assertTrue(
        written.value == "64500",
        lower.value == "64501",
        blocked.action == DecisionAction.Block,
        blocked.firedRules == List("deny_list:asn"),
        watched == Decision(DecisionAction.Allow, 20, List("watch_list:asn")),
        other == Decision.allow,
        none == Decision.allow,
      )
    },
    test("a country entry is accepted in either case, stored in upper case, and matches the country signal of the request") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def in(country: String) = ctx(signals = Map(GeoRules.CountrySignal -> country))
      for
        written <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.country, "kz")
        watch <- add(repository, lists, AntifraudListType.watch, AntifraudValueType.country, "Ru")
        blocked <- decide(lists)(in("KZ"))
        watched <- decide(lists)(in("RU"))
        other <- decide(lists)(in("DE"))
        none <- decide(lists)(ctx())
      yield assertTrue(
        written.value == "KZ",
        watch.value == "RU",
        blocked.action == DecisionAction.Block,
        blocked.firedRules == List("deny_list:country"),
        watched == Decision(DecisionAction.Allow, 20, List("watch_list:country")),
        other == Decision.allow,
        // without a country database the request has no country, and the entry matches nothing
        none == Decision.allow,
      )
    },
    test("a country entry is refused when it is not an alpha-2 code, and an allow on one is refused with the reason") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def deny(value: String) = add(repository, lists, AntifraudListType.deny, AntifraudValueType.country, value).either.map(_.isLeft)
      for
        three <- deny("KAZ")
        digit <- deny("K1")
        empty <- deny(" ")
        unknown <- deny("zz")
        allow <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.country, "KZ").either
      yield assertTrue(
        three,
        digit,
        empty,
        unknown,
        allow.swap.exists(e => e.isInstanceOf[InvalidListEntry] && e.getMessage.contains("exact subject")),
      )
    },
    test("a country entry names a whole population, so it needs an expiry no longer than max-broad-ttl, and a tenant's applies to its own requests") {
      val repository = FakeRepository()
      val (lists, _) = make(repository, settings.copy(maxBroadTtl = 30.days))
      def create(expiresAt: Option[Instant], tenantId: Option[TenantId] = None, value: String = "KZ") =
        put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.country,
            value = value,
            tenantId = tenantId,
            expiresAt = expiresAt,
            reason = None,
            source = "manual",
            shadow = false,
          ),
        )
          .either
      def in(tenantId: TenantId) = ctx(tenantId = Some(tenantId), signals = Map(GeoRules.CountrySignal -> "KZ"))
      for
        permanent <- create(None)
        tooLong <- create(Some(Instant.EPOCH.plusSeconds(31 * 86400)))
        atLimit <- create(Some(Instant.EPOCH.plusSeconds(30 * 86400)), tenantId = Some(tenant))
        own <- decide(lists)(in(tenant))
        foreign <- decide(lists)(in(TenantId("tenant-b")))
      yield assertTrue(
        permanent.isLeft,
        tooLong.swap.exists(_.getMessage.contains("30 days")),
        atLimit.isRight,
        own.action == DecisionAction.Block,
        foreign == Decision.allow,
      )
    },
    test("a request with no ASN (the database is off) matches no asn entry") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.asn, "64500")
        d <- decide(lists)(ctx(ip = Some("203.0.113.7"), subjectHash = Some("h")))
      yield assertTrue(d == Decision.allow)
    },
    test("an asn entry is refused when it is not a number: text, a negative or a number larger than an AS can be") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def deny(value: String) = add(repository, lists, AntifraudListType.deny, AntifraudValueType.asn, value).either.map(_.isLeft)
      for
        text <- deny("ASx")
        negative <- deny("-5")
        huge <- deny("4294967296")
        empty <- deny("AS")
      yield assertTrue(text, negative, huge, empty)
    },
    test("an allow on an asn is refused with the reason, so that a whole network is never exempted from the score") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      add(repository, lists, AntifraudListType.allow, AntifraudValueType.asn, "64500").either.map: result =>
        assertTrue(result.swap.exists(e => e.isInstanceOf[InvalidListEntry] && e.getMessage.contains("exact subject")))
    },
    test("an asn entry names a whole network, so it needs an expiry, and an address that is allowed inside a denied AS is allowed") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def deny(expiresAt: Option[Instant]) =
        put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.asn,
            value = "64500",
            tenantId = None,
            expiresAt = expiresAt,
            reason = None,
            source = "manual",
            shadow = false,
          ),
        )
          .either
          .map(_.isRight)
      for
        permanent <- deny(None)
        _ <- deny(Some(Instant.EPOCH.plusSeconds(3600)))
        _ <- add(repository, lists, AntifraudListType.allow, AntifraudValueType.ip, "203.0.113.7")
        inside <- decide(lists)(ctx(asn = Some(64500), ip = Some("203.0.113.7")))
        beside <- decide(lists)(ctx(asn = Some(64500), ip = Some("203.0.113.8")))
      yield assertTrue(!permanent, inside.action == DecisionAction.Allow, beside.action == DecisionAction.Block)
    },
    test("an asn entry of a tenant applies to that tenant's requests only") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.asn, "64500", tenantId = Some(tenant))
        own <- decide(lists)(ctx(asn = Some(64500)))
        foreign <- decide(lists)(ctx(asn = Some(64500), tenantId = Some(TenantId("tenant-b"))))
      yield assertTrue(own.action == DecisionAction.Block, foreign == Decision.allow)
    },
    test("an entry that names many clients, or an automated one, needs an expiry") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def create(valueType: AntifraudValueType, value: String, source: String, expiresAt: Option[Instant]) =
        put(
          repository,
          lists,
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
        )
          .either
          .map(_.isRight)
      val later = Some(Instant.EPOCH.plusSeconds(3600))
      for
        broad <- create(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("ua"), "manual", None)
        wide <- create(AntifraudValueType.cidr, "10.0.0.0/16", "manual", None)
        prefix <- create(AntifraudValueType.prefix, "4915", "manual", None)
        narrow <- create(AntifraudValueType.cidr, "10.1.0.0/24", "manual", None)
        exact <- create(AntifraudValueType.phone_hash, "d", "manual", None)
        automated <- create(AntifraudValueType.phone_hash, "d2", "rule:velocity", None)
        automatedExpiring <- create(AntifraudValueType.phone_hash, "d3", "rule:velocity", later)
        broadExpiring <- create(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("ua2"), "manual", later)
      yield assertTrue(!broad, !wide, !prefix, narrow, exact, !automated, automatedExpiring, broadExpiring)
    },
    test("a network wider than /8 (IPv4) or /32 (IPv6) is refused, the limit itself is not, and an address is not a network") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def deny(valueType: AntifraudValueType, value: String) = add(repository, lists, AntifraudListType.deny, valueType, value).either
      for
        everybody <- deny(AntifraudValueType.cidr, "0.0.0.0/0")
        seven <- deny(AntifraudValueType.cidr, "10.0.0.0/7")
        eight <- deny(AntifraudValueType.cidr, "10.0.0.0/8")
        sixteen <- deny(AntifraudValueType.cidr, "10.1.0.0/16")
        everybody6 <- deny(AntifraudValueType.cidr, "::/0")
        thirtyOne <- deny(AntifraudValueType.cidr, "2001:db8::/31")
        thirtyTwo <- deny(AntifraudValueType.cidr, "2001:db8::/32")
        ip <- deny(AntifraudValueType.ip, "10.0.0.1")
      yield assertTrue(
        everybody.swap.exists(_.getMessage.contains("/8")),
        seven.isLeft,
        eight.isRight,
        sixteen.isRight,
        everybody6.swap.exists(_.getMessage.contains("/32")),
        thirtyOne.isLeft,
        thirtyTwo.isRight,
        ip.isRight,
      )
    },
    test("a broad network, a mail domain or a prefix lives 30 days at most, and exact subjects and the other kinds are not held to it") {
      val repository = FakeRepository()
      val (lists, _) = make(repository, AntifraudTestSettings.lists.copy(enabled = true))
      def create(valueType: AntifraudValueType, value: String, days: Long) =
        put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = valueType,
            value = value,
            tenantId = None,
            expiresAt = Some(Instant.EPOCH.plusSeconds(days * 86400)),
            reason = None,
            source = ListEntrySource.Manual,
            shadow = false,
          ),
        )
          .either
      for
        prefix <- create(AntifraudValueType.prefix, "4915", 31)
        domain <- create(AntifraudValueType.email_domain, "mailinator.com", 31)
        cidr <- create(AntifraudValueType.cidr, "10.0.0.0/16", 31)
        exactNetwork <- create(AntifraudValueType.cidr, "10.0.0.1/32", 31)
        narrowNetwork <- create(AntifraudValueType.cidr, "10.3.0.0/24", 365)
        prefixAtLimit <- create(AntifraudValueType.prefix, "4916", 30)
        cidrAtLimit <- create(AntifraudValueType.cidr, "10.2.0.0/16", 30)
        phone <- create(AntifraudValueType.phone_hash, "h", 365)
        ua <- create(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("ua"), 365)
      yield assertTrue(
        prefix.swap.exists(_.getMessage.contains("30 days")),
        domain.isLeft,
        cidr.isLeft,
        // A network of one address, or a /24, names one subject or few: held to nothing, as before.
        exactNetwork.isRight,
        narrowNetwork.isRight,
        prefixAtLimit.isRight,
        cidrAtLimit.isRight,
        phone.isRight,
        ua.isRight,
      )
    },
    test("the limit of a lifetime is the configured one") {
      val repository = FakeRepository()
      val (lists, _) = make(repository, AntifraudTestSettings.lists.copy(enabled = true, maxBroadTtl = 3.days))
      def prefix(days: Long) =
        put(
          repository,
          lists,
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.prefix,
            value = "4915",
            tenantId = None,
            expiresAt = Some(Instant.EPOCH.plusSeconds(days * 86400)),
            reason = None,
            source = ListEntrySource.Manual,
            shadow = false,
          ),
        )
          .either
      for
        long <- prefix(4)
        short <- prefix(3)
      yield assertTrue(long.isLeft, short.isRight)
    },
    test("a preview is over the traffic of the entry's tenant, and of all of it for a global entry, of the last 24 hours") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def entry(tenantId: Option[TenantId]) =
        NewListEntry(
          listType = AntifraudListType.deny,
          valueType = AntifraudValueType.prefix,
          value = "4915",
          tenantId = tenantId,
          expiresAt = Some(Instant.EPOCH.plusSeconds(60)),
          reason = None,
          source = ListEntrySource.Manual,
          shadow = false,
        )
      for
        now <- Clock.instant
        _ <- lists.preview(entry(Some(tenant)))
        _ <- lists.preview(entry(None))
      yield assertTrue(
        repository.previewedFor == List(Some(tenant), None),
        repository.previewedSince == List(now.minusSeconds(24 * 3600), now.minusSeconds(24 * 3600)),
      )
    },
    test("a shadow entry enforces nothing and only shows in the fired rules") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d", shadow = true)
        d <- decide(lists)(ctx(subjectHash = Some("d")))
      yield assertTrue(d == Decision(DecisionAction.Allow, 0, List("shadow_list:phone_hash")))
    },
    test(
      "a preview answers the canonical value and what the entry would have matched, and counts a value as it is asked: whole, not cut to a range",
    ) {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      def entry(valueType: AntifraudValueType, value: String) =
        NewListEntry(
          listType = AntifraudListType.deny,
          valueType = valueType,
          value = value,
          tenantId = None,
          expiresAt = Some(Instant.EPOCH.plusSeconds(60)),
          reason = None,
          source = "manual",
          shadow = false,
        )
      for
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = Some(600), total = 1000) }
        counted <- lists.preview(entry(AntifraudValueType.prefix, "491512"))
        _ <- ZIO.succeed { repository.previewResult = ListPreview(matched = None, total = 1000) }
        // a network: a kind the event log has no column for, so the preview cannot count it
        uncounted <- lists.preview(entry(AntifraudValueType.asn, "AS64500"))
      yield assertTrue(
        counted == ("491512", ListPreview(matched = Some(600), total = 1000)),
        uncounted == ("64500", ListPreview(matched = None, total = 1000)),
        repository.previewedValues == List("491512", "64500"),
      )
    },
    test("a preview refuses an entry that is not valid, as a check does, and counts nothing") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        bad <- lists.preview(
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.cidr,
            value = "10.0.0.0/40",
            tenantId = None,
            expiresAt = None,
            reason = None,
            source = "manual",
            shadow = false,
          ),
        ).either
      yield assertTrue(bad.left.exists(_.isInstanceOf[InvalidListEntry]), repository.previewedValues.isEmpty)
    },
    test("hits asks the repository for the counters of the ids, and refuses more than the most one call may ask") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      val id = UUID.randomUUID()
      val at = Instant.parse("2026-11-01T00:00:00Z")
      for
        _ <- repository.addHits(List(ListHit(id, 4, at), ListHit(UUID.randomUUID(), 1, at)))
        counted <- lists.hits(List(id))
        most <- lists.hits(List.fill(AntifraudLists.MaxLimit)(id)).either
        tooMany <- lists.hits(List.fill(AntifraudLists.MaxLimit + 1)(id)).either
      yield assertTrue(
        counted == List(ListHit(id, 4, at)),
        most.isRight,
        tooMany.left.exists(_.isInstanceOf[InvalidListEntry]),
        // the refused call never reached the repository
        repository.hitsAsked.size == 2,
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
              PolicyListEntry(
                id = UUID.randomUUID(),
                listType = "deny",
                valueType = "phone_hash",
                value = "d",
                tenantId = None,
                expiresAt = None,
                reason = None,
                source = "manual",
                shadow = false,
                createdBy = "t",
                createdAt = Instant.EPOCH,
              ),
            )
          }
          fiber <- lists.start.fork
          _ <- TestClock.adjust(2.seconds) *> ZIO.yieldNow.repeatN(20)
          stillStarting <- fiber.poll.map(_.isEmpty)
          _ <- ZIO.succeed { repository.failLoad = false }
          _ <- TestClock.adjust(1.second) *> ZIO.yieldNow.repeatN(20)
          _ <- fiber.join
          d <- decide(lists)(ctx(subjectHash = Some("d")))
        yield assertTrue(stillStarting, d.action == DecisionAction.Block)
    },
    test("an unchanged table is not read again, except once a minute for a change made behind the service's back") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        afterCreate <- ZIO.succeed(repository.loads)
        _ <- lists.refresh.repeatN(9)
        unchanged <- ZIO.succeed(repository.loads)
        // by hand in SQL: no version bump
        _ <- ZIO.succeed { repository.entries = Nil }
        _ <- lists.refresh
        stillThere <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- TestClock.adjust(61.seconds)
        _ <- lists.refresh
        gone <- decide(lists)(ctx(subjectHash = Some("d")))
      yield assertTrue(unchanged == afterCreate, stillThere.action == DecisionAction.Block, gone == Decision.allow)
    },
    test("a hit on a shadow entry is counted as one, apart from a hit on an enforcing entry of the same kind") {
      val repository = FakeRepository()
      val (lists, cache) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d", shadow = true)
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.prefix, "4915")
        _ <- decide(lists)(ctx(subjectHash = Some("d")))
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
      yield assertTrue(
        cache.drainKinds().toMap == Map(
          (AntifraudListType.deny, AntifraudValueType.phone_hash, true) -> 1L,
          (AntifraudListType.deny, AntifraudValueType.prefix, false) -> 2L,
        ),
      )
    },
    test("every rule the lists contribute is in the catalogue of rule names the settings are checked against") {
      val repository = FakeRepository()
      val (lists, _) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.deny, AntifraudValueType.phone_hash, "d")
        rules <- lists.ruleSet.rules
      yield assertTrue(
        rules.nonEmpty,
        rules.forall(r => Antifraud.ruleNames.contains(r.name)),
        Antifraud.ruleNames.filter(_.contains("_list:")) == rules.map(_.name).toSet,
      )
    },
    test("counts hits by list and kind of value, for the metric") {
      val repository = FakeRepository()
      val (lists, cache) = make(repository)
      for
        _ <- add(repository, lists, AntifraudListType.fake, AntifraudValueType.prefix, "4915")
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
        _ <- decide(lists)(ctx(destPrefix = Some("491512")))
      yield assertTrue(cache.drainKinds() == List((AntifraudListType.fake, AntifraudValueType.prefix, false) -> 2L), cache.drainKinds().isEmpty)
    },
  )
