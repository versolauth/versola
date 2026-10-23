package versola.central.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.tenants.TenantId
import versola.central.users.AuthClient
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudListServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenant = TenantId("tenant-a")
  private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")

  private val newEntry = NewListEntry(
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = Some("4915"),
    phone = None,
    tenantId = None,
    expiresAt = None,
    reason = None,
    source = None,
    shadow = None,
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
    createdBy = "alice",
    createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    hitCount = 0,
    lastHitAt = None,
  )

  private val operator = AdminCaller(subject = Some("alice"), tenant = Some(TenantId("default")))
  private val tenantAdmin = AdminCaller(subject = Some("bob"), tenant = Some(tenant))

  private val noQuery = ListEntriesQuery(listType = None, valueType = None, tenantId = None, global = None, limit = None, offset = None)

  def spec = suite("AntifraudListService")(
    test("an admin of the default tenant is an operator: auth gets their name and no tenant") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      for
        _ <- auth.createAntifraudListEntry.succeedsWith(stored)
        created <- service.create(newEntry, operator)
      yield assertTrue(created == stored, auth.createAntifraudListEntry.calls == List((newEntry, AntifraudListActor("alice", None))))
    },
    test("an admin of another tenant is limited to it, for every call") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      val actor = AntifraudListActor("bob", Some(tenant))
      for
        _ <- auth.createAntifraudListEntry.succeedsWith(stored)
        _ <- auth.previewAntifraudListEntry.succeedsWith(PreviewResponse(matched = None, total = 0))
        _ <- auth.listAntifraudListEntries.succeedsWith(List(stored))
        _ <- auth.deleteAntifraudListEntry.succeedsWith(())
        _ <- auth.antifraudListAudit.succeedsWith(Nil)
        _ <- service.create(newEntry, tenantAdmin)
        _ <- service.preview(newEntry, tenantAdmin)
        listed <- service.list(noQuery, tenantAdmin)
        _ <- service.delete(entryId, tenantAdmin)
        _ <- service.audit(Some(entryId), Some(3), tenantAdmin)
      yield assertTrue(
        listed == ListEntriesResponse(List(stored)),
        auth.createAntifraudListEntry.calls.map(_._2) == List(actor),
        auth.previewAntifraudListEntry.calls.map(_._2) == List(actor),
        auth.listAntifraudListEntries.calls == List((noQuery, actor)),
        auth.deleteAntifraudListEntry.calls == List((entryId, actor)),
        auth.antifraudListAudit.calls == List((Some(entryId), Some(3), actor)),
      )
    },
    test("a request that names no admin, or half of one, is refused before auth is asked") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      def refused(caller: AdminCaller) = service.create(newEntry, caller).either.zip(service.list(noQuery, caller).either)
      for
        none <- refused(AdminCaller(subject = None, tenant = None))
        noTenant <- refused(AdminCaller(subject = Some("alice"), tenant = None))
        noSubject <- refused(AdminCaller(subject = None, tenant = Some(TenantId("default"))))
        emptySubject <- refused(AdminCaller(subject = Some(""), tenant = Some(TenantId("default"))))
      yield assertTrue(
        List(none, noTenant, noSubject, emptySubject).forall(_ == (Left(AntifraudListError.NoCaller), Left(AntifraudListError.NoCaller))),
        auth.createAntifraudListEntry.calls.isEmpty,
        auth.listAntifraudListEntries.calls.isEmpty,
      )
    },
    test("auth's refusals and failures come through unchanged") {
      val auth = stub[AuthClient]
      val service = AntifraudListService.Impl(auth)
      for
        _ <- auth.createAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
        invalid <- service.create(newEntry, operator).either
        _ <- auth.deleteAntifraudListEntry.failsWith(AntifraudListError.NotFound)
        missing <- service.delete(entryId, operator).either
        _ <- auth.antifraudListAudit.failsWith(AntifraudListError.Forbidden("only an operator reads the audit"))
        forbidden <- service.audit(None, None, tenantAdmin).either
      yield assertTrue(
        invalid == Left(AntifraudListError.Invalid("not an IP address")),
        missing == Left(AntifraudListError.NotFound),
        forbidden == Left(AntifraudListError.Forbidden("only an operator reads the audit")),
      )
    },
  )
