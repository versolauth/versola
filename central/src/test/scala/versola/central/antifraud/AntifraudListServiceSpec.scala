package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.central.users.{AuthClient, UserId}
import versola.util.{PageLimit, PageOffset, UnitSpecBase}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudListServiceSpec extends UnitSpecBase:
  private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")
  private val tenantCaller = AntifraudCaller.Tenant(tenantA)
  private val now = Instant.parse("2026-11-20T10:00:00Z")

  private val request = NewListEntryRequest(
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = Some("4915"),
    phone = None,
    tenantId = None,
    expiresAt = None,
    reason = Some("attack"),
    shadow = None,
    userId = admin,
    callerTenantId = None,
  )

  private def record(tenantId: Option[TenantId], value: String = "4915", shadow: Boolean = false) = ListEntryRecord(
    id = entryId,
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = value,
    tenantId = tenantId,
    expiresAt = None,
    reason = Some("attack"),
    source = ListEntrySource.Manual,
    shadow = shadow,
    createdBy = admin.toString,
    createdAt = now,
  )

  private def checkOf(tenantId: Option[TenantId], shadow: Boolean) = ListEntryCheck(
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = Some("4915"),
    phone = None,
    tenantId = tenantId,
    expiresAt = None,
    reason = Some("attack"),
    source = ListEntrySource.Manual,
    shadow = shadow,
    preview = false,
  )

  private def response(entry: ListEntryRecord, hitCount: Long = 0, lastHitAt: Option[Instant] = None) = ListEntryResponse(
    id = entry.id,
    listType = entry.listType,
    valueType = entry.valueType,
    value = entry.value,
    tenantId = entry.tenantId,
    expiresAt = entry.expiresAt,
    reason = entry.reason,
    source = entry.source,
    shadow = entry.shadow,
    createdBy = entry.createdBy,
    createdAt = entry.createdAt,
    hitCount = hitCount,
    lastHitAt = lastHitAt,
  )

  private val noQuery = ListEntriesQuery(listType = None, valueType = None, tenantId = None, global = None, limit = None, offset = None)

  class Env:
    val repository = stub[AntifraudListRepository]
    val auth = stub[AuthClient]
    val service = AntifraudListService.Impl(repository, auth)

    /** The repository stores what it is given, as it would. */
    def storing = repository.upsert.returnsZIO((entry, _, _) => ZIO.succeed(entry))

  def spec = suite("AntifraudListService")(
    suite("create")(
      test("an enforcing entry is previewed by auth, and what is stored is the value auth answered, with who added it and when") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(1), total = 1000))))
          _ <- env.storing
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          created <- env.service.create(request.copy(tenantId = Some(tenantA)), AntifraudCaller.Operator)
          stored = env.repository.upsert.calls.head
        yield assertTrue(
          env.auth.previewAntifraudListEntry.calls == List(checkOf(Some(tenantA), shadow = false)),
          env.auth.checkAntifraudListEntry.calls.isEmpty,
          stored._1.value == "4915",
          stored._1.tenantId.contains(tenantA),
          stored._1.createdBy == admin.toString,
          stored._1.createdAt == now,
          stored._1.source == ListEntrySource.Manual,
          stored._2 == admin.toString,
          stored._3 == now,
          created.value == "4915",
          created.createdBy == admin.toString,
        )
      },
      test("the value auth made of the request is stored, not the request's: a phone is stored as its hash") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.checkAntifraudListEntry.succeedsWith(CheckedListEntry("hash-of-phone", None))
          _ <- env.storing
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          created <- env.service.create(
            request.copy(valueType = AntifraudValueType.phone_hash, value = None, phone = Some("+77011234567"), shadow = Some(true)),
            AntifraudCaller.Operator,
          )
        yield assertTrue(
          env.auth.checkAntifraudListEntry.calls.map(_.phone) == List(Some("+77011234567")),
          env.repository.upsert.calls.map(_._1.value) == List("hash-of-phone"),
          created.value == "hash-of-phone",
          created.shadow,
        )
      },
      test("a shadow entry is only checked by auth, never previewed, so its share of the traffic is not judged") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.checkAntifraudListEntry.succeedsWith(CheckedListEntry("4915", None))
          _ <- env.storing
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          created <- env.service.create(request.copy(shadow = Some(true)), AntifraudCaller.Operator)
        yield assertTrue(
          env.auth.checkAntifraudListEntry.calls == List(checkOf(None, shadow = true)),
          env.auth.previewAntifraudListEntry.calls.isEmpty,
          env.repository.upsert.calls.size == 1,
          created.shadow,
        )
      },
      test("the source is manual whatever the caller is") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = None, total = 0))))
          _ <- env.storing
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          _ <- env.service.create(request, tenantCaller)
        yield assertTrue(
          env.auth.previewAntifraudListEntry.calls.map(_.source) == List(ListEntrySource.Manual),
          env.repository.upsert.calls.map(_._1.source) == List(ListEntrySource.Manual),
        )
      },
      suite("the share of the traffic")(
        test("an enforcing entry that would have matched more than 5% of 100 or more events is refused and stores nothing") {
          val env = Env()
          for
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(6), total = 100))))
            refused <- env.service.create(request, AntifraudCaller.Operator).either
          yield assertTrue(
            refused.left.exists:
              case AntifraudListError.Invalid(message) => message.contains("6 of 100") && message.contains("shadow")
              case _ => false
            ,
            env.repository.upsert.calls.isEmpty,
          )
        },
        test("exactly 5% is allowed, and one more matched is not") {
          val env = Env()
          for
            _ <- TestClock.setTime(now)
            _ <- env.storing
            _ <- env.auth.antifraudListHits.succeedsWith(Nil)
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(50), total = 1000))))
            allowed <- env.service.create(request, AntifraudCaller.Operator).either
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(51), total = 1000))))
            refused <- env.service.create(request, AntifraudCaller.Operator).either
          yield assertTrue(allowed.isRight, refused.left.exists(_.isInstanceOf[AntifraudListError.Invalid]), env.repository.upsert.calls.size == 1)
        },
        test("fewer than 100 events are no basis for a refusal, and 100 are") {
          val env = Env()
          for
            _ <- TestClock.setTime(now)
            _ <- env.storing
            _ <- env.auth.antifraudListHits.succeedsWith(Nil)
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(99), total = 99))))
            few <- env.service.create(request, AntifraudCaller.Operator).either
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(100), total = 100))))
            enough <- env.service.create(request, AntifraudCaller.Operator).either
          yield assertTrue(few.isRight, enough.left.exists(_.isInstanceOf[AntifraudListError.Invalid]), env.repository.upsert.calls.size == 1)
        },
        test("an entry of a value no cell counts (matched unknown) is not refused") {
          val env = Env()
          for
            _ <- TestClock.setTime(now)
            _ <- env.storing
            _ <- env.auth.antifraudListHits.succeedsWith(Nil)
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("10.0.0.1", Some(PreviewResponse(matched = None, total = 100000))))
            created <- env.service.create(request.copy(valueType = AntifraudValueType.ip, value = Some("10.0.0.1")), AntifraudCaller.Operator).either
          yield assertTrue(created.isRight)
        },
      ),
      suite("scope")(
        test("a tenant admin's entry is its tenant's whether the body names the tenant or not") {
          val env = Env()
          for
            _ <- TestClock.setTime(now)
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(0), total = 1000))))
            _ <- env.storing
            _ <- env.auth.antifraudListHits.succeedsWith(Nil)
            unnamed <- env.service.create(request, tenantCaller)
            named <- env.service.create(request.copy(tenantId = Some(tenantA)), tenantCaller)
          yield assertTrue(unnamed.tenantId.contains(tenantA), named.tenantId.contains(tenantA))
        },
        test("a tenant admin adding an entry of another tenant, or a global one by naming none, is refused before auth is asked") {
          val env = Env()
          for
            other <- env.service.create(request.copy(tenantId = Some(tenantB)), tenantCaller).either
            otherPreview <- env.service.preview(request.copy(tenantId = Some(tenantB)), tenantCaller).either
          yield assertTrue(
            other.left.exists(_.isInstanceOf[AntifraudListError.Forbidden]),
            otherPreview.left.exists(_.isInstanceOf[AntifraudListError.Forbidden]),
            env.auth.previewAntifraudListEntry.calls.isEmpty,
            env.auth.checkAntifraudListEntry.calls.isEmpty,
            env.repository.upsert.calls.isEmpty,
          )
        },
        test("an operator may add a global entry and any tenant's") {
          val env = Env()
          for
            _ <- TestClock.setTime(now)
            _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(0), total = 1000))))
            _ <- env.storing
            _ <- env.auth.antifraudListHits.succeedsWith(Nil)
            global <- env.service.create(request, AntifraudCaller.Operator)
            scoped <- env.service.create(request.copy(tenantId = Some(tenantB)), AntifraudCaller.Operator)
          yield assertTrue(global.tenantId.isEmpty, scoped.tenantId.contains(tenantB))
        },
      ),
      test("auth's refusal of the entry comes through unchanged and stores nothing") {
        val env = Env()
        for
          _ <- env.auth.previewAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
          _ <- env.auth.checkAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
          invalid <- env.service.create(request, AntifraudCaller.Operator).either
          invalidShadow <- env.service.create(request.copy(shadow = Some(true)), AntifraudCaller.Operator).either
        yield assertTrue(
          invalid == Left(AntifraudListError.Invalid("not an IP address")),
          invalidShadow == Left(AntifraudListError.Invalid("not an IP address")),
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("the hits the cells counted are in the answer, and an entry nobody counted has none") {
        val env = Env()
        val lastAt = Instant.parse("2026-11-19T00:00:00Z")
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(0), total = 1000))))
          _ <- env.storing
          _ <- env.auth.antifraudListHits.succeedsWith(List(ListHits(id = UUID.randomUUID(), count = 9, lastAt = lastAt)))
          created <- env.service.create(request, AntifraudCaller.Operator)
        yield assertTrue(created.hitCount == 0, created.lastHitAt.isEmpty)
      },
    ),
    suite("preview")(
      test("answers what auth counted for the entry as central made it, and stores nothing") {
        val env = Env()
        for
          _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", Some(PreviewResponse(matched = Some(3), total = 10))))
          previewed <- env.service.preview(request, tenantCaller)
        yield assertTrue(
          previewed == PreviewResponse(matched = Some(3), total = 10),
          env.auth.previewAntifraudListEntry.calls == List(checkOf(Some(tenantA), shadow = false)),
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("fails when auth answers without a preview") {
        val env = Env()
        for
          _ <- env.auth.previewAntifraudListEntry.succeedsWith(CheckedListEntry("4915", None))
          failed <- env.service.preview(request, AntifraudCaller.Operator).either
        yield assertTrue(failed.left.exists(_.isInstanceOf[RuntimeException]))
      },
    ),
    suite("list")(
      test("answers the entries with the hits of each, and asks the cells for the ids listed") {
        val env = Env()
        val other = record(Some(tenantA)).copy(id = UUID.fromString("00000000-0000-0000-0000-0000000000e2"), value = "4916")
        val first = record(None)
        val lastAt = Instant.parse("2026-11-19T00:00:00Z")
        for
          _ <- env.repository.list.succeedsWith(List(first, other))
          _ <- env.auth.antifraudListHits.succeedsWith(List(ListHits(id = first.id, count = 7, lastAt = lastAt)))
          listed <- env.service.list(noQuery, AntifraudCaller.Operator)
        yield assertTrue(
          listed == ListEntriesResponse(List(response(first, hitCount = 7, lastHitAt = Some(lastAt)), response(other))),
          env.auth.antifraudListHits.calls == List(List(first.id, other.id)),
        )
      },
      test("an operator's listing is unrestricted, a tenant admin's is limited to the global entries and its own") {
        val env = Env()
        for
          _ <- env.repository.list.succeedsWith(Nil)
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          _ <- env.service.list(noQuery, AntifraudCaller.Operator)
          _ <- env.service.list(noQuery, tenantCaller)
        yield assertTrue(env.repository.list.calls.map(_._1.visibleTo) == List(None, Some(tenantA)))
      },
      test("the filter passes the list, the value type, the tenant or the global entries only, and the page") {
        val env = Env()
        for
          _ <- env.repository.list.succeedsWith(Nil)
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          _ <- env.service.list(
            noQuery.copy(
              listType = Some(AntifraudListType.watch),
              valueType = Some(AntifraudValueType.asn),
              tenantId = Some(tenantB),
              limit = Some(PageLimit(5)),
              offset = Some(PageOffset(10)),
            ),
            AntifraudCaller.Operator,
          )
          _ <- env.service.list(noQuery.copy(global = Some(true)), AntifraudCaller.Operator)
          _ <- env.service.list(noQuery.copy(global = Some(false)), AntifraudCaller.Operator)
        yield assertTrue(
          env.repository.list.calls.map(c => (c._1.listType, c._1.valueType, c._1.tenantId, c._2, c._3)) == List(
            (Some(AntifraudListType.watch), Some(AntifraudValueType.asn), Some(Some(tenantB)), 5, 10),
            (None, None, Some(None), PageLimit.Default, PageOffset.First),
            (None, None, None, PageLimit.Default, PageOffset.First),
          ),
        )
      },
      test("naming a tenant and asking for the global entries only is invalid") {
        val env = Env()
        for failed <- env.service.list(noQuery.copy(tenantId = Some(tenantA), global = Some(true)), AntifraudCaller.Operator).either
        yield assertTrue(failed.left.exists(_.isInstanceOf[AntifraudListError.Invalid]), env.repository.list.calls.isEmpty)
      },
      test("a tenant admin may narrow a listing to its own tenant and to no other") {
        val env = Env()
        for
          _ <- env.repository.list.succeedsWith(Nil)
          _ <- env.auth.antifraudListHits.succeedsWith(Nil)
          own <- env.service.list(noQuery.copy(tenantId = Some(tenantA)), tenantCaller).either
          other <- env.service.list(noQuery.copy(tenantId = Some(tenantB)), tenantCaller).either
        yield assertTrue(
          own.isRight,
          other.left.exists(_.isInstanceOf[AntifraudListError.Forbidden]),
          env.repository.list.calls.size == 1,
        )
      },
    ),
    suite("delete")(
      test("removes the entry as the admin, at the time of the call") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.repository.find.succeedsWith(Some(record(Some(tenantA))))
          _ <- env.repository.delete.succeedsWith(true)
          _ <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), tenantCaller)
          _ <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), AntifraudCaller.Operator)
        yield assertTrue(env.repository.delete.calls == List((entryId, admin.toString, now), (entryId, admin.toString, now)))
      },
      test("an operator may remove a global entry") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(record(None)))
          _ <- env.repository.delete.succeedsWith(true)
          removed <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), AntifraudCaller.Operator).either
        yield assertTrue(removed.isRight, env.repository.delete.calls.size == 1)
      },
      test("a tenant admin may not remove another tenant's entry or a global one, and nothing is removed") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(record(Some(tenantB))))
          ofOther <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), tenantCaller).either
          _ <- env.repository.find.succeedsWith(Some(record(None)))
          global <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), tenantCaller).either
        yield assertTrue(
          ofOther.left.exists(_.isInstanceOf[AntifraudListError.Forbidden]),
          global.left.exists(_.isInstanceOf[AntifraudListError.Forbidden]),
          env.repository.delete.calls.isEmpty,
        )
      },
      test("an entry that is not there is not found, for every scope") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(None)
          operator <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), AntifraudCaller.Operator).either
          tenant <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), tenantCaller).either
        yield assertTrue(
          operator == Left(AntifraudListError.NotFound),
          tenant == Left(AntifraudListError.NotFound),
          env.repository.delete.calls.isEmpty,
        )
      },
      test("an entry removed by someone else between the read and the delete is not found") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(record(None)))
          _ <- env.repository.delete.succeedsWith(false)
          gone <- env.service.delete(DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = None), AntifraudCaller.Operator).either
        yield assertTrue(gone == Left(AntifraudListError.NotFound))
      },
    ),
    suite("audit")(
      test("an operator reads the audit of all, a tenant admin the audit of the global entries and its own") {
        val env = Env()
        for
          _ <- env.repository.audit.succeedsWith(Nil)
          operator <- env.service.audit(Some(entryId), Some(PageLimit(3)), AntifraudCaller.Operator)
          tenant <- env.service.audit(None, None, tenantCaller)
        yield assertTrue(
          operator == AuditResponse(Nil),
          tenant == AuditResponse(Nil),
          env.repository.audit.calls == List((Some(entryId), 3, None), (None, PageLimit.Default, Some(tenantA))),
        )
      },
    ),
    suite("active")(
      test("is what the repository holds at the time of the call") {
        val env = Env()
        val lists = PolicyLists(version = 4, entries = List(record(None)))
        for
          _ <- TestClock.setTime(now)
          _ <- env.repository.active.succeedsWith(lists)
          active <- env.service.active
        yield assertTrue(active == lists, env.repository.active.calls == List(now))
      },
    ),
    suite("cleanUp")(
      test("purges the expired entries and prunes the audit older than its retention") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.repository.purgeExpired.succeedsWith(3)
          _ <- env.repository.pruneAudit.succeedsWith(0)
          _ <- env.service.cleanUp
        yield assertTrue(
          env.repository.purgeExpired.calls == List((now, 1000)),
          env.repository.pruneAudit.calls == List((now.minusSeconds(AntifraudListService.AuditRetention.toSeconds), 1000)),
        )
      },
      test("goes on in batches, with a rest between them, until one comes back short") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          batches <- Ref.make(List(1000, 1000, 5))
          _ <- env.repository.purgeExpired.returnsZIO((_, _) => batches.modify(b => (b.head, b.tail)))
          _ <- env.repository.pruneAudit.succeedsWith(0)
          fiber <- env.service.cleanUp.fork
          _ <- TestClock.adjust(1.second)
          _ <- fiber.join
        yield assertTrue(env.repository.purgeExpired.calls.size == 3, env.repository.pruneAudit.calls.size == 1)
      },
      test("a failure is logged and not raised, and the audit is still pruned") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.repository.purgeExpired.failsWith(new RuntimeException("database is down"))
          _ <- env.repository.pruneAudit.succeedsWith(0)
          _ <- env.service.cleanUp
        yield assertTrue(env.repository.purgeExpired.calls.size == 1, env.repository.pruneAudit.calls.size == 1)
      } @@ TestAspect.silentLogging,
    ),
  )
