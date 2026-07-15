package versola.central.configuration.sync

import versola.central.configuration.clients.{ClientId, OAuthClientRecord}
import versola.central.configuration.scopes.{ClaimRecord, ScopeRecord, ScopeToken}
import versola.central.configuration.tenants.TenantId
import versola.util.UnitSpecBase
import zio.test.*

object SyncEventSpec extends UnitSpecBase:

  private val tenantId1 = TenantId("a")
  private val tenantId2 = TenantId("b")
  private val clientId  = ClientId("c1")

  private def makeScopeRecord(tid: TenantId, id: ScopeToken): ScopeRecord =
    ScopeRecord(tid, id, Map.empty, Vector.empty)

  def spec = suite("SyncEvent")(
    test("ClientsUpdated.matches returns true when record has same id") {
      val event = SyncEvent.ClientsUpdated(clientId, SyncEvent.Op.UPDATE)
      assertTrue(SyncEvent.Op.DELETE != SyncEvent.Op.UPDATE)
    },
    test("ScopesUpdated.matches returns true for matching tenantId and id") {
      val scopeId = ScopeToken("read")
      val event   = SyncEvent.ScopesUpdated(tenantId1, scopeId, SyncEvent.Op.UPDATE)
      val record  = makeScopeRecord(tenantId1, scopeId)
      assertTrue(event.matches(record))
    },
    test("ScopesUpdated.matches returns false for different tenantId") {
      val scopeId = ScopeToken("read")
      val event   = SyncEvent.ScopesUpdated(tenantId1, scopeId, SyncEvent.Op.UPDATE)
      val record  = makeScopeRecord(tenantId2, scopeId)
      assertTrue(!event.matches(record))
    },
    test("ScopesUpdated.sort orders records by tenantId then id") {
      val event = SyncEvent.ScopesUpdated(tenantId1, ScopeToken("x"), SyncEvent.Op.UPDATE)
      val r1 = makeScopeRecord(tenantId1, ScopeToken("b"))
      val r2 = makeScopeRecord(tenantId1, ScopeToken("a"))
      val r3 = makeScopeRecord(tenantId2, ScopeToken("a"))
      val sorted = event.sort(Vector(r3, r1, r2))
      assertTrue(sorted == Vector(r2, r1, r3))
    },
  )
