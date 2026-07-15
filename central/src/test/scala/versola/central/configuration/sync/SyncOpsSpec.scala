package versola.central.configuration.sync

import versola.central.configuration.clients.ClientId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

object SyncOpsSpec extends UnitSpecBase:

  def spec = suite("SyncOps")(
    test("syncCache with DELETE removes matching record") {
      val tenantId = TenantId("t1")
      val scopeId  = ScopeToken("read")
      import versola.central.configuration.scopes.ScopeRecord
      val record = ScopeRecord(tenantId, scopeId, Map.empty, Vector.empty)
      val cache  = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Vector(record))))
      val event  = SyncEvent.ScopesUpdated(tenantId, scopeId, SyncEvent.Op.DELETE)
      for
        _ <- SyncOps.syncCache(event)(cache, ZIO.succeed(None))
        result <- cache.get
      yield assertTrue(result.isEmpty)
    },
    test("syncCache with UPDATE inserts fetched record") {
      val tenantId = TenantId("t1")
      val scopeId  = ScopeToken("write")
      import versola.central.configuration.scopes.ScopeRecord
      val record = ScopeRecord(tenantId, scopeId, Map.empty, Vector.empty)
      val cache  = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Vector.empty[ScopeRecord])))
      val event  = SyncEvent.ScopesUpdated(tenantId, scopeId, SyncEvent.Op.UPDATE)
      for
        _ <- SyncOps.syncCache(event)(cache, ZIO.succeed(Some(record)))
        result <- cache.get
      yield assertTrue(result == Vector(record))
    },
  )
