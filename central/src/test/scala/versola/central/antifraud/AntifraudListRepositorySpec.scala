package versola.central.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** What every [[AntifraudListRepository]] promises: entries stored with their audit (an update with what it replaced),
  * refreshed on a repeat and kept apart by scope, filtered and paged, expired and pruned in batches, and counted for the
  * version a cell compares its copy with. */
trait AntifraudListRepositorySpec extends DatabaseSpecBase[AntifraudListRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudListRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("stores an entry with every column and reads it back, with an audit record") {
        val tenant = Some(TenantId("tenant-a"))
        val e = entry(
          listType = AntifraudListType.fake,
          valueType = AntifraudValueType.cidr,
          value = "10.0.0.0/8",
          tenantId = tenant,
          expiresAt = Some(at.plusSeconds(3600)),
        )
        for
          stored <- repository.upsert(e, "alice", at)
          listed <- repository.list(all, 10, 0)
          found <- repository.find(e.id)
          audit <- repository.audit(None, 10, None)
        yield assertTrue(
          stored == e,
          listed == List(e),
          found.contains(e),
          audit.map(a => (a.action, a.actor, a.entryId, a.listType, a.valueType, a.value, a.tenantId, a.expiresAt, a.reason, a.previous)) ==
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
              None,
            )),
        )
      },
      test("find answers none for an entry that is not there") {
        for found <- repository.find(UUID.randomUUID())
        yield assertTrue(found.isEmpty)
      },
      test("adding the same value in the same scope again refreshes it, keeps the id and the author, and audits what it replaced") {
        for
          first <- repository.upsert(entry(shadow = true), "alice", at)
          again <- repository.upsert(
            entry().copy(expiresAt = Some(at.plusSeconds(60)), reason = Some("again"), source = "rule", createdBy = "bob"),
            "bob",
            at.plusSeconds(1),
          )
          tenantScoped <- repository.upsert(entry(tenantId = Some(TenantId("t"))), "alice", at)
          otherList <- repository.upsert(entry(AntifraudListType.watch), "alice", at)
          listed <- repository.list(all, 10, 0)
          audit <- repository.audit(Some(first.id), 10, None)
        yield assertTrue(
          again.id == first.id,
          again.expiresAt.contains(at.plusSeconds(60)),
          again.reason.contains("again"),
          again.source == "rule",
          !again.shadow,
          again.createdBy == "alice",
          tenantScoped.id != first.id,
          otherList.id != first.id,
          listed.size == 3,
          audit.map(a => (a.action, a.actor)) == List((AntifraudListAction.update, "bob"), (AntifraudListAction.create, "alice")),
          audit.head.expiresAt.contains(at.plusSeconds(60)),
          audit.head.previous.contains(
            PreviousListValuesResponse(expiresAt = None, reason = Some("seen in an attack"), source = "manual", shadow = true),
          ),
        )
      },
      test("filters by list, value type and scope; pages, the newest first") {
        for
          global <- repository.upsert(entry(value = "a"), "x", at)
          scoped <- repository.upsert(entry(value = "b", tenantId = Some(TenantId("t"))).copy(createdAt = at.plusSeconds(1)), "x", at)
          watch <- repository.upsert(
            entry(AntifraudListType.watch, AntifraudValueType.asn, "64500").copy(createdAt = at.plusSeconds(2)),
            "x",
            at,
          )
          denies <- repository.list(all.copy(listType = Some(AntifraudListType.deny)), 10, 0)
          asns <- repository.list(all.copy(valueType = Some(AntifraudValueType.asn)), 10, 0)
          globals <- repository.list(all.copy(tenantId = Some(None)), 10, 0)
          tenants <- repository.list(all.copy(tenantId = Some(Some(TenantId("t")))), 10, 0)
          everything <- repository.list(all, 10, 0)
          page <- repository.list(all, 2, 2)
        yield assertTrue(
          denies.map(_.id).toSet == Set(global.id, scoped.id),
          asns.map(_.id) == List(watch.id),
          globals.map(_.id).toSet == Set(global.id, watch.id),
          tenants.map(_.id) == List(scoped.id),
          everything.map(_.id) == List(watch.id, scoped.id, global.id),
          page.map(_.id) == List(global.id),
        )
      },
      test("a listing and the audit can be limited to what a tenant sees: the global entries and its own") {
        val a = Some(TenantId("tenant-a"))
        val b = Some(TenantId("tenant-b"))
        for
          global <- repository.upsert(entry(value = "g"), "x", at)
          own <- repository.upsert(entry(value = "a", tenantId = a), "x", at.plusSeconds(1))
          other <- repository.upsert(entry(value = "b", tenantId = b), "x", at.plusSeconds(2))
          seenByA <- repository.list(all.copy(visibleTo = a), 10, 0)
          seenByB <- repository.list(all.copy(visibleTo = b), 10, 0)
          everything <- repository.list(all, 10, 0)
          onlyOwn <- repository.list(all.copy(tenantId = Some(a), visibleTo = a), 10, 0)
          noneOfOthers <- repository.list(all.copy(tenantId = Some(b), visibleTo = a), 10, 0)
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
      test("the audit of one entry holds its own changes only, the latest first, and is limited") {
        for
          one <- repository.upsert(entry(value = "one"), "alice", at)
          two <- repository.upsert(entry(value = "two"), "alice", at.plusSeconds(1))
          _ <- repository.delete(one.id, "bob", at.plusSeconds(2))
          ofOne <- repository.audit(Some(one.id), 10, None)
          ofTwo <- repository.audit(Some(two.id), 10, None)
          everything <- repository.audit(None, 10, None)
          limited <- repository.audit(None, 2, None)
        yield assertTrue(
          ofOne.map(_.action) == List(AntifraudListAction.delete, AntifraudListAction.create),
          ofTwo.map(_.action) == List(AntifraudListAction.create),
          everything.map(a => (a.entryId, a.action)) ==
            List((one.id, AntifraudListAction.delete), (two.id, AntifraudListAction.create), (one.id, AntifraudListAction.create)),
          limited.size == 2,
        )
      },
      test("deletes an entry, records who did, and says so when there was none") {
        for
          e <- repository.upsert(entry(), "alice", at)
          removed <- repository.delete(e.id, "bob", at.plusSeconds(5))
          again <- repository.delete(e.id, "bob", at.plusSeconds(6))
          listed <- repository.list(all, 10, 0)
          found <- repository.find(e.id)
          audit <- repository.audit(Some(e.id), 10, None)
        yield assertTrue(
          removed,
          !again,
          listed.isEmpty,
          found.isEmpty,
          audit.map(a => (a.action, a.actor, a.value)) == List(
            (AntifraudListAction.delete, "bob", "dev-1"),
            (AntifraudListAction.create, "alice", "dev-1"),
          ),
        )
      },
      test("active holds what has not expired, with the version they were read at") {
        for
          forever <- repository.upsert(entry(value = "forever"), "x", at)
          later <- repository.upsert(entry(value = "later", expiresAt = Some(at.plusSeconds(10))), "x", at)
          _ <- repository.upsert(entry(value = "gone", expiresAt = Some(at)), "x", at)
          _ <- repository.upsert(entry(value = "past", expiresAt = Some(at.minusSeconds(10))), "x", at)
          version <- repository.version
          active <- repository.active(at)
        yield assertTrue(
          active.version == version,
          active.entries.map(_.id).toSet == Set(forever.id, later.id),
          active.entries.find(_.id == later.id).flatMap(_.expiresAt).contains(at.plusSeconds(10)),
        )
      },
      test("keeps the shadow flag and refreshes it on a repeat") {
        for
          first <- repository.upsert(entry(shadow = true), "x", at)
          loaded <- repository.active(at)
          again <- repository.upsert(entry(shadow = false), "x", at)
          found <- repository.find(first.id)
        yield assertTrue(first.shadow, loaded.entries.map(_.shadow) == List(true), !again.shadow, found.map(_.shadow).contains(false))
      },
      test("removes expired entries and records each as expired") {
        for
          live <- repository.upsert(entry(value = "live", expiresAt = Some(at.plusSeconds(60))), "x", at)
          old <- repository.upsert(entry(value = "old", expiresAt = Some(at.plusSeconds(1))), "x", at)
          n <- repository.purgeExpired(at.plusSeconds(2), 1000)
          listed <- repository.list(all, 10, 0)
          audit <- repository.audit(Some(old.id), 10, None)
        yield assertTrue(
          n == 1,
          listed.map(_.id) == List(live.id),
          audit.map(a => (a.action, a.actor)).head == (AntifraudListAction.expire, "system"),
        )
      },
      test("every change moves the version and nothing else does") {
        for
          v0 <- repository.version
          e <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(1))), "x", at)
          v1 <- repository.version
          _ <- repository.list(all, 10, 0)
          _ <- repository.find(e.id)
          _ <- repository.audit(None, 10, None)
          _ <- repository.active(at)
          v2 <- repository.version
          // nothing is due yet
          _ <- repository.purgeExpired(at, 10)
          v3 <- repository.version
          _ <- repository.purgeExpired(at.plusSeconds(5), 10)
          v4 <- repository.version
          _ <- repository.purgeExpired(at.plusSeconds(5), 10)
          v5 <- repository.version
          // a delete of what is not there changes nothing
          _ <- repository.delete(e.id, "x", at)
          v6 <- repository.version
          e2 <- repository.upsert(entry(), "x", at)
          _ <- repository.delete(e2.id, "x", at)
          v7 <- repository.version
          _ <- repository.pruneAudit(at.plusSeconds(1000), 100)
          v8 <- repository.version
        yield assertTrue(v1 == v0 + 1, v2 == v1, v3 == v2, v4 == v3 + 1, v5 == v4, v6 == v5, v7 == v6 + 2, v8 == v7)
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
    )

object AntifraudListRepositorySpec:
  case class Env(repository: AntifraudListRepository)

  val at: Instant = Instant.parse("2026-10-23T10:00:00Z")

  val all: ListEntryFilter = ListEntryFilter(listType = None, valueType = None, tenantId = None, visibleTo = None)

  def entry(
      listType: AntifraudListType = AntifraudListType.deny,
      valueType: AntifraudValueType = AntifraudValueType.device_id,
      value: String = "dev-1",
      tenantId: Option[TenantId] = None,
      expiresAt: Option[Instant] = None,
      shadow: Boolean = false,
  ): ListEntryRecord = ListEntryRecord(
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
