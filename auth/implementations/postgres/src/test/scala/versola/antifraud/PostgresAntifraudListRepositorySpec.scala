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
        n <- repository.purgeExpired(at.plusSeconds(2))
        listed <- repository.list(AntifraudListFilter.all, 10, 0)
        audit <- repository.audit(Some(old.id), 10)
      yield assertTrue(n == 1, listed.map(_.id) == List(live.id), audit.map(a => (a.action, a.actor)).head == (AntifraudListAction.expire, "system"))
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
        byPhone <- repository.preview(AntifraudValueType.phone_hash, "h1", since)
        byPrefix <- repository.preview(AntifraudValueType.prefix, "4915", since)
        byUa <- repository.preview(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex(ua), since)
        byIp <- repository.preview(AntifraudValueType.ip, "10.0.0.1", since)
      yield assertTrue(
        byPhone == ListPreview(matched = Some(1), total = 3),
        byPrefix == ListPreview(matched = Some(1), total = 3),
        byUa == ListPreview(matched = Some(1), total = 3),
        byIp == ListPreview(matched = None, total = 3),
      )
      // PostgresAntifraudEventRepositorySpec runs at the same time and drops the partitions of af_event; when it
      // does so in the middle of this, the test is repeated.
    } @@ TestAspect.eventually,
    test("the table refuses a list or value type it does not know") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        bad <- xa.connect(
          sql"""INSERT INTO af_list_entry (id, type, value_type, value, source, created_by, created_at)
                VALUES (${UUID.randomUUID()}, 'ban', 'ip', 'x', 's', 'c', now())""".update.run(),
        ).exit
      yield assertTrue(bad.isFailure)
    },
  ) @@ TestAspect.sequential
