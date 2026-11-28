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
      test("creating and deleting an entry move the version, and reads, a purge and a prune do not") {
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
        yield assertTrue(v1 == v0 + 1, v2 == v1, v3 == v2, v4 == v3, v5 == v4, v6 == v5, v7 == v6 + 2, v8 == v7)
      },
      test("a purge of expired entries writes its audit and deletes them but leaves the version, and active does not change") {
        for
          live <- repository.upsert(entry(value = "live", expiresAt = Some(at.plusSeconds(60))), "x", at)
          old <- repository.upsert(entry(value = "old", expiresAt = Some(at.plusSeconds(1))), "x", at)
          before <- repository.active(at.plusSeconds(2))
          purged <- repository.purgeExpired(at.plusSeconds(2), 10)
          after <- repository.active(at.plusSeconds(2))
          audit <- repository.audit(Some(old.id), 10, None)
          version <- repository.version
        yield assertTrue(
          purged == 1,
          before.entries == after.entries,
          after.entries.map(_.id) == List(live.id),
          after.version == before.version,
          version == before.version,
          audit.map(_.action).contains(AntifraudListAction.expire),
        )
      },
      test("an upsert that finds the entry as it is leaves the version, and still records the request in the audit") {
        val e = entry(expiresAt = Some(at.plusSeconds(60)))
        for
          first <- repository.upsert(e, "alice", at)
          v1 <- repository.version
          again <- repository.upsert(e.copy(id = UUID.randomUUID(), createdBy = "bob"), "bob", at.plusSeconds(1))
          v2 <- repository.version
          audit <- repository.audit(Some(first.id), 10, None)
        yield assertTrue(again.id == first.id, v2 == v1, audit.map(_.action).toSet == Set(AntifraudListAction.create, AntifraudListAction.update))
      },
      test("an upsert that changes the expiry, the reason, the source or the shadow flag moves the version, each by one") {
        val base = entry(expiresAt = Some(at.plusSeconds(60)))
        val changes = List(
          base.copy(expiresAt = Some(at.plusSeconds(120))),
          base.copy(expiresAt = None),
          base.copy(reason = Some("another reason")),
          base.copy(reason = None),
          base.copy(source = "rule"),
          base.copy(shadow = true),
        )
        for
          _ <- repository.upsert(base, "x", at)
          // Each change is made from the stored entry as the one before left it, and checked on its own.
          moved <- ZIO.foreach(changes): change =>
            for
              _ <- repository.upsert(base, "x", at)
              before <- repository.version
              _ <- repository.upsert(change, "x", at)
              after <- repository.version
            yield after - before
        yield assertTrue(moved == List.fill(changes.size)(1L))
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
      // versolauth/versola#1052: the lists by delta
      test("lists without `since` is the whole list, full, with the version") {
        for
          a <- repository.upsert(entry(value = "a"), "x", at)
          b <- repository.upsert(entry(value = "b", expiresAt = Some(at.plusSeconds(60))), "x", at)
          _ <- repository.upsert(entry(value = "expired", expiresAt = Some(at.minusSeconds(1))), "x", at)
          version <- repository.version
          lists <- repository.lists(None, at)
          active <- repository.active(at)
        yield assertTrue(
          lists.full,
          lists.version == version,
          lists.entries.map(_.id).toSet == Set(a.id, b.id),
          lists.removed.isEmpty,
          active == lists,
        )
      },
      test("lists with `since` equal to the version is an empty delta") {
        for
          _ <- repository.upsert(entry(), "x", at)
          version <- repository.version
          lists <- repository.lists(Some(version), at)
        yield assertTrue(lists == PolicyLists(version = version, full = false, entries = Nil, removed = Nil))
      },
      test("lists on an empty database with `since` 0 is an empty delta") {
        for lists <- repository.lists(Some(0), at)
        yield assertTrue(lists == PolicyLists(version = 0, full = false, entries = Nil, removed = Nil))
      },
      test("one upsert is that entry, and only it") {
        for
          _ <- repository.upsert(entry(value = "old"), "x", at)
          since <- repository.version
          e <- repository.upsert(entry(value = "new"), "x", at.plusSeconds(1))
          version <- repository.version
          lists <- repository.lists(Some(since), at)
        yield assertTrue(lists.full == false, lists.version == version, lists.entries == List(e), lists.removed.isEmpty)
      },
      test("one delete is its id in `removed`") {
        for
          e <- repository.upsert(entry(), "x", at)
          since <- repository.version
          _ <- repository.delete(e.id, "x", at.plusSeconds(1))
          version <- repository.version
          lists <- repository.lists(Some(since), at)
        yield assertTrue(lists == PolicyLists(version = version, full = false, entries = Nil, removed = List(e.id)))
      },
      test("an upsert then a delete of the same entry is only a removal") {
        for
          since <- repository.version
          e <- repository.upsert(entry(), "x", at)
          _ <- repository.delete(e.id, "x", at.plusSeconds(1))
          version <- repository.version
          lists <- repository.lists(Some(since), at)
        yield assertTrue(version == since + 2, lists == PolicyLists(version = version, full = false, entries = Nil, removed = List(e.id)))
      },
      test("a delete then a new entry of the same value is the old id removed and the new entry added") {
        for
          old <- repository.upsert(entry(value = "same"), "x", at)
          since <- repository.version
          _ <- repository.delete(old.id, "x", at.plusSeconds(1))
          fresh <- repository.upsert(entry(value = "same"), "x", at.plusSeconds(2))
          lists <- repository.lists(Some(since), at)
        yield assertTrue(
          fresh.id != old.id,
          !lists.full,
          lists.entries == List(fresh),
          lists.removed == List(old.id),
        )
      },
      test("an entry changed twice is in the answer once, as it is now") {
        val base = entry(expiresAt = Some(at.plusSeconds(60)))
        for
          first <- repository.upsert(base, "x", at)
          since <- repository.version
          _ <- repository.upsert(base.copy(reason = Some("second")), "x", at.plusSeconds(1))
          third <- repository.upsert(base.copy(reason = Some("third")), "x", at.plusSeconds(2))
          lists <- repository.lists(Some(since), at)
        yield assertTrue(
          third.id == first.id,
          !lists.full,
          lists.entries == List(third),
          lists.entries.head.reason.contains("third"),
          lists.removed.isEmpty,
        )
      },
      test("an entry a change names that has expired since is in `removed`, not in `entries`") {
        for
          since <- repository.version
          soon <- repository.upsert(entry(value = "soon", expiresAt = Some(at.plusSeconds(10))), "x", at)
          later <- repository.upsert(entry(value = "later", expiresAt = Some(at.plusSeconds(100))), "x", at)
          lists <- repository.lists(Some(since), at.plusSeconds(20))
          before <- repository.lists(Some(since), at)
        yield assertTrue(
          !lists.full,
          lists.entries.map(_.id) == List(later.id),
          lists.removed == List(soon.id),
          before.entries.map(_.id).toSet == Set(soon.id, later.id),
          before.removed.isEmpty,
        )
      },
      test("an entry expired and purged is in `removed` as well") {
        for
          since <- repository.version
          soon <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(10))), "x", at)
          _ <- repository.purgeExpired(at.plusSeconds(20), 10)
          lists <- repository.lists(Some(since), at.plusSeconds(20))
        yield assertTrue(!lists.full, lists.entries.isEmpty, lists.removed == List(soon.id))
      },
      test("`since` ahead of the version is the whole list") {
        for
          e <- repository.upsert(entry(), "x", at)
          version <- repository.version
          ahead <- repository.lists(Some(version + 1), at)
          far <- repository.lists(Some(version + 1000), at)
        yield assertTrue(
          ahead.full,
          far.full,
          ahead.version == version,
          ahead.entries == List(e),
          ahead.removed.isEmpty,
        )
      },
      test("a negative `since` is the whole list") {
        for
          e <- repository.upsert(entry(), "x", at)
          lists <- repository.lists(Some(-5), at)
        yield assertTrue(lists.full, lists.entries == List(e))
      },
      test("a gap in the changes is the whole list, and a delta is answered only where every version has its change") {
        for
          a <- repository.upsert(entry(value = "a"), "x", at)
          b <- repository.upsert(entry(value = "b"), "x", at.plusSeconds(100))
          c <- repository.upsert(entry(value = "c"), "x", at.plusSeconds(200))
          version <- repository.version
          // the change of version 1 is pruned: a copy at version 0 would miss `a`
          pruned <- repository.pruneChanges(at.plusSeconds(50), 100)
          fromZero <- repository.lists(Some(0), at)
          fromOne <- repository.lists(Some(1), at)
        yield assertTrue(
          pruned == 1,
          fromZero.full,
          fromZero.entries.map(_.id).toSet == Set(a.id, b.id, c.id),
          fromZero.removed.isEmpty,
          !fromOne.full,
          fromOne.version == version,
          fromOne.entries.map(_.id).toSet == Set(b.id, c.id),
        )
      },
      test("a change pruned from the middle is a gap too") {
        for
          _ <- repository.upsert(entry(value = "a"), "x", at)
          _ <- repository.upsert(entry(value = "b"), "x", at.plusSeconds(1000))
          _ <- repository.upsert(entry(value = "c"), "x", at.plusSeconds(1))
          // 'b' kept (late), the other two are older: pruning at +500 removes versions 1 and 3
          pruned <- repository.pruneChanges(at.plusSeconds(500), 100)
          lists <- repository.lists(Some(0), at)
          partial <- repository.lists(Some(1), at)
        yield assertTrue(pruned == 2, lists.full, partial.full)
      },
      test("pruning the changes removes those older than the limit and nothing newer, in batches") {
        for
          _ <- ZIO.foreachDiscard(1 to 5)(i => repository.upsert(entry(value = s"v$i"), "x", at.plusSeconds(i * 10L)))
          none <- repository.pruneChanges(at, 10)
          exactly <- repository.pruneChanges(at.plusSeconds(10), 10)
          some <- repository.pruneChanges(at.plusSeconds(45), 2)
          rest <- repository.pruneChanges(at.plusSeconds(45), 2)
          again <- repository.pruneChanges(at.plusSeconds(45), 2)
          newest <- repository.lists(Some(4), at)
          older <- repository.lists(Some(3), at)
          oldest <- repository.lists(Some(0), at)
        yield assertTrue(
          none == 0,
          exactly == 0,
          some == 2,
          rest == 2,
          again == 0,
          // version 5 (at +50 s) is left, and is the only delta that can be answered
          !newest.full && newest.entries.size == 1,
          older.full,
          oldest.full,
        )
      },
      test("a purge of expired entries writes no change: the version and the delta stay") {
        for
          e <- repository.upsert(entry(expiresAt = Some(at.plusSeconds(10))), "x", at)
          version <- repository.version
          purged <- repository.purgeExpired(at.plusSeconds(20), 10)
          after <- repository.version
          current <- repository.lists(Some(version), at.plusSeconds(20))
          fromZero <- repository.lists(Some(0), at.plusSeconds(20))
        yield assertTrue(
          purged == 1,
          after == version,
          current == PolicyLists(version = version, full = false, entries = Nil, removed = Nil),
          !fromZero.full,
          fromZero.removed == List(e.id),
        )
      },
      test("an upsert that changes nothing writes no change, so the delta does not name the entry") {
        val e = entry(expiresAt = Some(at.plusSeconds(60)))
        for
          _ <- repository.upsert(e, "x", at)
          since <- repository.version
          _ <- repository.upsert(e.copy(id = UUID.randomUUID()), "x", at.plusSeconds(1))
          lists <- repository.lists(Some(since), at)
        yield assertTrue(lists == PolicyLists(version = since, full = false, entries = Nil, removed = Nil))
      },
      test("the version of the answer is the highest change it reflects, and the next delta continues from it") {
        for
          _ <- repository.upsert(entry(value = "a"), "x", at)
          first <- repository.lists(Some(0), at)
          b <- repository.upsert(entry(value = "b"), "x", at.plusSeconds(1))
          second <- repository.lists(Some(first.version), at)
          version <- repository.version
        yield assertTrue(
          first.version == 1,
          !first.full,
          second.version == version,
          second.version == 2,
          second.entries == List(b),
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
