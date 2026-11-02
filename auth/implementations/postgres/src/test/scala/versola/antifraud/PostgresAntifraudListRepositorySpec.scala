package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PostgresAntifraudListRepositorySpec extends PostgresSpec:

  private val at = Instant.parse("2026-10-23T10:00:00Z")

  private def entry(
      listType: AntifraudListType = AntifraudListType.deny,
      valueType: AntifraudValueType = AntifraudValueType.device_id,
      value: String = "dev-1",
      tenantId: Option[TenantId] = None,
      expiresAt: Option[Instant] = None,
      shadow: Boolean = false,
  ) = AntifraudListEntry(
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

  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudListRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_list_entry".update.run())
      _ <- xa.connect(sql"DELETE FROM af_list_audit".update.run())
    yield PostgresAntifraudListRepository(xa)

  def spec = suite("PostgresAntifraudListRepository")(
    test("stores an entry with every column and reads it back, with an audit record") {
      for
        repository <- clean
        tenant = Some(TenantId("tenant-a"))
        e = entry(AntifraudListType.fake, AntifraudValueType.cidr, "10.0.0.0/8", tenant, Some(at.plusSeconds(3600)))
        stored <- repository.upsert(e, "alice", at)
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
        audit <- repository.audit(None, 10)
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
        repository <- clean
        first <- repository.upsert(entry(), "alice", at)
        again <- repository.upsert(
          entry().copy(expiresAt = Some(at.plusSeconds(60)), reason = Some("again"), source = "rule", createdBy = "bob"),
          "bob",
          at.plusSeconds(1),
        )
        tenantScoped <- repository.upsert(entry(tenantId = Some(TenantId("t"))), "alice", at)
        otherList <- repository.upsert(entry(AntifraudListType.watch), "alice", at)
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
        audit <- repository.audit(Some(first.id), 10)
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
        repository <- clean
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
        repository <- clean
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
    test("deletes an entry, records who did, and says so when there was none") {
      for
        repository <- clean
        e <- repository.upsert(entry(), "alice", at)
        removed <- repository.delete(e.id, "bob", at.plusSeconds(5))
        again <- repository.delete(e.id, "bob", at.plusSeconds(6))
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
        audit <- repository.audit(Some(e.id), 10)
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
        repository <- clean
        live <- repository.upsert(entry(value = "live", expiresAt = Some(at.plusSeconds(60))), "x", at)
        old <- repository.upsert(entry(value = "old", expiresAt = Some(at.plusSeconds(1))), "x", at)
        n <- repository.purgeExpired(at.plusSeconds(2), 1000)
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
        audit <- repository.audit(Some(old.id), 10)
      yield assertTrue(n == 1, listed.map(_.id) == List(live.id), audit.map(a => (a.action, a.actor)).head == (AntifraudListAction.expire, "system"))
    },
    test("every change bumps the version and nothing else does") {
      for
        repository <- clean
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
        repository <- clean
        _ <- ZIO.foreachDiscard(1 to 5)(i => repository.upsert(entry(value = s"d$i", expiresAt = Some(at.plusSeconds(i))), "x", at.plusSeconds(i)))
        first <- repository.purgeExpired(at.plusSeconds(100), 2)
        second <- repository.purgeExpired(at.plusSeconds(100), 2)
        third <- repository.purgeExpired(at.plusSeconds(100), 2)
        fourth <- repository.purgeExpired(at.plusSeconds(100), 2)
        // 5 'create' audit records at +1..+5 s and 5 'expire' at +100 s
        none <- repository.pruneAudit(at, 10)
        some <- repository.pruneAudit(at.plusSeconds(50), 3)
        rest <- repository.pruneAudit(at.plusSeconds(50), 3)
        left <- repository.audit(None, 100)
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
        repository <- clean
        e <- repository.upsert(entry(), "x", at)
        _ <- repository.addHits(List(ListHit(e.id, 3, at.plusSeconds(10)), ListHit(UUID.randomUUID(), 5, at)))
        _ <- repository.addHits(List(ListHit(e.id, 2, at.plusSeconds(5))))
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
      yield assertTrue(listed.map(l => (l.hitCount, l.lastHitAt)) == List((5L, Some(at.plusSeconds(10)))))
    },
    test("keeps the shadow flag, refreshes it on a repeat, and loads it for the cache") {
      for
        repository <- clean
        first <- repository.upsert(entry(shadow = true), "x", at)
        loaded <- repository.loadActive(at)
        again <- repository.upsert(entry(shadow = false), "x", at)
        found <- repository.find(first.id)
        missing <- repository.find(UUID.randomUUID())
      yield assertTrue(first.shadow, loaded.map(_.shadow) == List(true), !again.shadow, found.map(_.shadow).contains(false), missing.isEmpty)
    },
    test("previews what an entry would have matched of the event log, for the values the log has a column for") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        events = PostgresAntifraudEventRepository(xa)
        today = at.atZone(java.time.ZoneOffset.UTC).toLocalDate
        _ <- events.maintainPartitions(today.minusDays(2), 3, today.minusDays(30))
        ua = "Mozilla/5.0 (preview)"
        base = AntifraudEvent(
          id = UUID.randomUUID(),
          at = at.minusSeconds(60),
          tenantId = None,
          clientId = versola.oauth.client.model.ClientId("c"),
          conversationId = UUID.randomUUID(),
          action = AntifraudAction.otp_request,
          outcome = AntifraudOutcome.sent,
          channel = AntifraudChannel.sms,
          subjectHash = "h1",
          destPrefix = Some("491512"),
          userAgent = Some(ua),
          score = None,
          rules = Nil,
        )
        _ <- xa.connect(sql"DELETE FROM af_event WHERE at >= ${at.minusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)}".update.run())
        _ <- events.insertBatch(
          List(
            base,
            base.copy(id = UUID.randomUUID(), subjectHash = "h2", destPrefix = Some("491612"), userAgent = Some("other")),
            base.copy(id = UUID.randomUUID(), subjectHash = "h3", destPrefix = Some("33123"), userAgent = None),
            base.copy(id = UUID.randomUUID(), at = at.minusSeconds(48 * 3600), subjectHash = "h1"),
          ),
        )
        since = at.minusSeconds(3600)
        byPhone <- repository.preview(AntifraudValueType.phone_hash, "h1", None, since)
        byUa <- repository.preview(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex(ua), None, since)
        byIp <- repository.preview(AntifraudValueType.ip, "10.0.0.1", None, since)
      yield assertTrue(
        byPhone == ListPreview(matched = Some(1), total = 3),
        byUa == ListPreview(matched = Some(1), total = 3),
        byIp == ListPreview(matched = None, total = 3),
      )
      // PostgresAntifraudEventRepositorySpec runs at the same time and drops the partitions of af_event; when it
      // does so in the middle of this, the test is repeated.
    } @@ TestAspect.eventually,
    test("a preview is over the events of the entry's tenant, and of all of them for a global entry") {
      val a = Some(TenantId("tenant-a"))
      val b = Some(TenantId("tenant-b"))
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        events = PostgresAntifraudEventRepository(xa)
        today = at.atZone(java.time.ZoneOffset.UTC).toLocalDate
        _ <- events.maintainPartitions(today.minusDays(2), 3, today.minusDays(30))
        base = AntifraudEvent(
          id = UUID.randomUUID(),
          at = at.minusSeconds(60),
          tenantId = a,
          clientId = versola.oauth.client.model.ClientId("c"),
          conversationId = UUID.randomUUID(),
          action = AntifraudAction.otp_request,
          outcome = AntifraudOutcome.sent,
          channel = AntifraudChannel.sms,
          subjectHash = "h1",
          destPrefix = Some("491512"),
          userAgent = Some("Mozilla/5.0 (tenants)"),
          score = None,
          rules = Nil,
        )
        _ <- xa.connect(sql"DELETE FROM af_event WHERE at >= ${at.minusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)}".update.run())
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
      yield assertTrue(
        global == ListPreview(matched = Some(5), total = 6),
        ofA == ListPreview(matched = Some(1), total = 2),
        ofB == ListPreview(matched = Some(3), total = 3),
        uaOfA == ListPreview(matched = Some(2), total = 2),
        ipOfB == ListPreview(matched = None, total = 3),
      )
    } @@ TestAspect.eventually,
    test("a preview of a prefix reads the rolled-up requests of the range, of the entry's tenant, and a longer prefix is its range") {
      val since = at.minusSeconds(3600)
      def stats(tenant: String, prefix: String, minute: Instant, requests: Int)(using DbCon) =
        sql"""INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled, provider_errors, verified, wrong_codes)
              VALUES ($tenant, $prefix, ${minute.atOffset(java.time.ZoneOffset.UTC)}, $requests, 0, 0, 0, 0, 0, 0)""".update.run()
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"DELETE FROM af_dest_stats".update.run())
        _ <- xa.connect:
          stats("tenant-a", "4915", since.plusSeconds(600), 30)
          stats("tenant-a", "4916", since.plusSeconds(660), 60)
          stats("tenant-b", "4915", since.plusSeconds(600), 10)
          // before the window
          stats("tenant-a", "4915", since.minusSeconds(60), 1000)
        global <- repository.preview(AntifraudValueType.prefix, "4915", None, since)
        ofA <- repository.preview(AntifraudValueType.prefix, "4915", Some(TenantId("tenant-a")), since)
        longer <- repository.preview(AntifraudValueType.prefix, "491512", Some(TenantId("tenant-a")), since)
        shorter <- repository.preview(AntifraudValueType.prefix, "49", Some(TenantId("tenant-a")), since)
        none <- repository.preview(AntifraudValueType.prefix, "33", Some(TenantId("tenant-a")), since)
        empty <- repository.preview(AntifraudValueType.prefix, "4915", Some(TenantId("nobody")), since)
      yield assertTrue(
        global == ListPreview(matched = Some(40), total = 100),
        ofA == ListPreview(matched = Some(30), total = 90),
        longer == ListPreview(matched = Some(30), total = 90),
        shorter == ListPreview(matched = Some(90), total = 90),
        none == ListPreview(matched = Some(0), total = 90),
        empty == ListPreview(matched = Some(0), total = 0),
      )
    } @@ TestAspect.eventually,
    test("an entry added again is audited as an update, with what it was; the first time as a create") {
      for
        repository <- clean
        first <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(3600))), "alice", at)
        again <- repository.upsert(
          entry().copy(expiresAt = Some(at.plusSeconds(60)), reason = Some("changed"), source = "detector:prefix_anomaly", shadow = true),
          "bob",
          at.plusSeconds(1),
        )
        audit <- repository.audit(Some(first.id), 10)
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
    test("every method is under the statement timeout: a lock held on its tables fails it instead of hanging") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        impatient = PostgresAntifraudListRepository(xa, statementTimeout = 100.millis)
        id = UUID.randomUUID()
        holder <- xa.transact:
          sql"LOCK TABLE af_list_entry, af_list_audit, af_list_version IN ACCESS EXCLUSIVE MODE".update.run()
          Thread.sleep(4000)
        .fork
        _ <- Live.live(ZIO.sleep(500.millis))
        calls = List(
          "upsert" -> impatient.upsert(entry(), "x", at).unit,
          "delete" -> impatient.delete(id, "x", at).unit,
          "find" -> impatient.find(id).unit,
          "list" -> impatient.list(AntifraudListFilter.all, 10, 0).unit,
          "loadActive" -> impatient.loadActive(at).unit,
          "version" -> impatient.version.unit,
          "addHits" -> impatient.addHits(List(ListHit(id, 1, at))),
          "purgeExpired" -> impatient.purgeExpired(at, 10).unit,
          "pruneAudit" -> impatient.pruneAudit(at, 10).unit,
          "audit of all" -> impatient.audit(None, 10).unit,
          "audit of one" -> impatient.audit(Some(id), 10).unit,
        )
        exits <- ZIO.foreach(calls)((name, call) => call.exit.map(name -> _))
        _ <- holder.join
      yield assertTrue(
        exits.filterNot(_._2.isFailure).map(_._1) == Nil,
        exits.forall(_._2.causeOption.exists(_.squash.getMessage.contains("statement timeout"))),
      )
    },
    test("pruning the audit also removes counter resets older than the limit, except the newest, which the generations count from") {
      val oldAt = at.minusSeconds(400L * 86400)
      def reset(generation: Long, when: Instant)(using DbCon) =
        sql"INSERT INTO af_counter_reset (generation, scope, requested_by, at) VALUES ($generation, 'all', 'x', ${when.atOffset(java.time.ZoneOffset.UTC)})".update.run()
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"DELETE FROM af_counter_reset".update.run())
        _ <- xa.connect:
          reset(1, oldAt)
          reset(2, oldAt)
          reset(3, at)
        _ <- repository.pruneAudit(at.minusSeconds(365L * 86400), 100)
        left <- xa.connect(sql"SELECT generation FROM af_counter_reset ORDER BY generation".query[Long].run().toList)
        // Everything is old, and the newest still stays.
        _ <- repository.pruneAudit(at.plusSeconds(400L * 86400), 100)
        newest <- xa.connect(sql"SELECT generation FROM af_counter_reset ORDER BY generation".query[Long].run().toList)
        _ <- xa.connect(sql"DELETE FROM af_counter_reset".update.run())
      yield assertTrue(left == List(3L), newest == List(3L))
    },
    test("a row of a kind this version does not know is stored, and skipped when the cache loads") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        now <- Clock.instant
        _ <- xa.connect(
          sql"""INSERT INTO af_list_entry (id, type, value_type, value, source, created_by, created_at)
                VALUES (${UUID.randomUUID()}, 'ban', 'ip', 'x', 's', 'c', now()),
                       (${UUID.randomUUID()}, 'deny', 'fingerprint', 'y', 's', 'c', now()),
                       (${UUID.randomUUID()}, 'deny', 'ip', '203.0.113.7', 's', 'c', now())""".update.run(),
        )
        loaded <- repository.loadActive(now)
      yield assertTrue(loaded.map(_.value) == List("203.0.113.7"))
    },
  ) @@ TestAspect.sequential
