package versola.central.configuration.scopes

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateClaim, CreateScopeRequest, PatchClaim, PatchDescription, PatchScope, UpdateScopeRequest}
import versola.util.ReloadingCache
import zio.prelude.EqualOps
import zio.*
import zio.test.*

object OAuthScopeServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val profileScope = ScopeToken("profile")
  private val emailScope = ScopeToken("email")
  private val emailClaim = Claim("email")
  private val nameClaim = Claim("name")
  private val localeClaim = Claim("locale")

  private val scopeRecord = ScopeRecord(
    tenantId = tenantId,
    id = profileScope,
    description = Map("en" -> "Profile"),
    claims = Vector(
      ClaimRecord(emailClaim, Map("en" -> "Email")),
      ClaimRecord(nameClaim, Map("en" -> "Name")),
    ),
  )

  private val otherTenantScope = ScopeRecord(
    tenantId = otherTenantId,
    id = emailScope,
    description = Map("en" -> "Email"),
    claims = Vector(ClaimRecord(emailClaim, Map("en" -> "Email"))),
  )

  private val createRequest = CreateScopeRequest(
    tenantId = tenantId,
    id = profileScope,
    description = Map("en" -> "Profile"),
    claims = List(
      CreateClaim(emailClaim, Map("en" -> "Email")),
      CreateClaim(nameClaim, Map("en" -> "Name")),
    ),
  )

  private val updateRequest = UpdateScopeRequest(
    tenantId = tenantId,
    id = profileScope,
    patch = PatchScope(
      add = List(CreateClaim(localeClaim, Map("en" -> "Locale"))),
      update = List(PatchClaim(emailClaim, PatchDescription(add = Map("ru" -> "Почта"), delete = Set.empty))),
      delete = Set(nameClaim),
      description = PatchDescription(add = Map("ru" -> "Профиль"), delete = Set.empty),
    ),
  )

  class Env(initial: Vector[ScopeRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OAuthScopeRepository]
    val service = OAuthScopeService.Impl(cache, repository)

  def spec = suite("OAuthScopeService")(
    test("getTenantScopes filters cache by tenant") {
      val env = new Env(Vector(scopeRecord, otherTenantScope))

      for
        result <- env.service.getTenantScopes(tenantId)
      yield assertTrue(result === Vector(scopeRecord))
    },
    test("getTenantScopes applies pagination after filtering") {
      val env = new Env(Vector(scopeRecord, scopeRecord.copy(id = ScopeToken("address")), otherTenantScope))
      val secondScope = scopeRecord.copy(id = ScopeToken("address"))

      for
        result <- env.service.getTenantScopes(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(secondScope))
    },
    test("createScope delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createScope.succeedsWith(())
        _ <- env.service.createScope(createRequest)
      yield assertTrue(
        env.repository.createScope.calls == List((tenantId, profileScope, createRequest.description, createRequest.claims))
      )
    },
    test("updateScope delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateScope.succeedsWith(())
        _ <- env.service.updateScope(updateRequest)
      yield assertTrue(env.repository.updateScope.calls == List((tenantId, profileScope, updateRequest.patch)))
    },
    test("deleteScope delegates tenant and scope id to repository") {
      val env = new Env()

      for
        _ <- env.repository.deleteScope.succeedsWith(())
        _ <- env.service.deleteScope(tenantId, profileScope)
      yield assertTrue(env.repository.deleteScope.calls === List((tenantId, profileScope)))
    },
    test("sync removes cached scope on delete event") {
      val env = new Env(Vector(scopeRecord, otherTenantScope))

      for
        _ <- env.service.sync(SyncEvent.ScopesUpdated(tenantId, profileScope, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached === Vector(otherTenantScope))
    },
    test("sync upserts fetched scope for non-delete event") {
      val env = new Env(Vector(scopeRecord, otherTenantScope))
      val updatedScope = scopeRecord.copy(description = Map("en" -> "Updated profile"))

      for
        _ <- env.repository.findScope.succeedsWith(Some(updatedScope))
        _ <- env.service.sync(SyncEvent.ScopesUpdated(tenantId, profileScope, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findScope.calls === List((tenantId, profileScope)),
        cached === Vector(updatedScope, otherTenantScope),
      )
    },
    test("sync removes cached scope when record is missing on non-delete event") {
      val env = new Env(Vector(scopeRecord, otherTenantScope))

      for
        _ <- env.repository.findScope.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.ScopesUpdated(tenantId, profileScope, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findScope.calls === List((tenantId, profileScope)),
        cached === Vector(otherTenantScope),
      )
    },
  )