package versola.central.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.central.users.{AuthClient, UserId}
import versola.util.PageLimit
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudListServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))

  private val request = NewListEntryRequest(
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = Some("4915"),
    phone = None,
    tenantId = None,
    expiresAt = None,
    reason = None,
    source = None,
    shadow = None,
    userId = admin,
  )

  private val stored = ListEntryResponse(
    id = entryId,
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = "4915",
    tenantId = None,
    expiresAt = None,
    reason = None,
    source = "manual",
    shadow = false,
    createdBy = admin.toString,
    createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    hitCount = 0,
    lastHitAt = None,
  )

  private val noQuery = ListEntriesQuery(listType = None, valueType = None, tenantId = None, global = None, limit = None, offset = None)

  def spec = suite("AntifraudListService")(
    test("every call goes to auth with the admin's userId, and the answers come back") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      val delete = DeleteListEntryRequest(id = entryId, userId = admin)
      for
        _ <- auth.createAntifraudListEntry.succeedsWith(stored)
        _ <- auth.previewAntifraudListEntry.succeedsWith(PreviewResponse(matched = None, total = 0))
        _ <- auth.listAntifraudListEntries.succeedsWith(List(stored))
        _ <- auth.deleteAntifraudListEntry.succeedsWith(())
        _ <- auth.antifraudListAudit.succeedsWith(Nil)
        created <- service.create(request)
        previewed <- service.preview(request)
        listed <- service.list(noQuery, admin)
        _ <- service.delete(delete)
        audited <- service.audit(Some(entryId), Some(PageLimit(3)), admin)
      yield assertTrue(
        created == stored,
        previewed == PreviewResponse(matched = None, total = 0),
        listed == ListEntriesResponse(List(stored)),
        audited == AuditResponse(Nil),
        auth.createAntifraudListEntry.calls == List(request),
        auth.previewAntifraudListEntry.calls == List(request),
        auth.listAntifraudListEntries.calls == List((noQuery, admin)),
        auth.deleteAntifraudListEntry.calls == List(delete),
        auth.antifraudListAudit.calls == List((Some(entryId), Some(PageLimit(3)), admin)),
      )
    },
    test("auth's refusals come through unchanged") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      for
        _ <- auth.createAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
        invalid <- service.create(request).either
        _ <- auth.deleteAntifraudListEntry.failsWith(AntifraudListError.NotFound)
        missing <- service.delete(DeleteListEntryRequest(id = entryId, userId = admin)).either
      yield assertTrue(
        invalid == Left(AntifraudListError.Invalid("not an IP address")),
        missing == Left(AntifraudListError.NotFound),
      )
    },
  )
