package versola.central.configuration.resources

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.{AclRuleTree, CreateResourceEndpointRequest, CreateResourceRequest, PermissionRule, ResourceUri, UpdateResourceRequest}
import versola.util.ReloadingCache
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object ResourceServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val resourceId = ResourceId(1)
  private val otherResourceId = ResourceId(2)
  private val existingEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000401")
  private val removedEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000402")
  private val createdEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000403")
  private val originalUri = ResourceUri("https://api.example.com")
  private val updatedUri = ResourceUri("https://api.internal.example.com")
  private val allowRule = PermissionRule("role", "equals", Json.Str("admin"))
  private val delegatedAllowRule = PermissionRule("department", "equals", Json.Str("support"))
  private val denyRule = PermissionRule("country", "equals", Json.Str("blocked"))
  private val allowRules = AclRuleTree.any(Vector(
    AclRuleTree.all(Vector(AclRuleTree.rule(allowRule))),
    AclRuleTree.all(Vector(AclRuleTree.rule(delegatedAllowRule))),
  ))
  private val denyRules = AclRuleTree.any(Vector(AclRuleTree.all(Vector(AclRuleTree.rule(denyRule)))))

  private val existingEndpoint = ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, AclRuleTree.emptyAny, AclRuleTree.emptyAny, Map.empty)
  private val removedEndpoint = ResourceEndpointRecord(removedEndpointId, "/users", "DELETE", false, AclRuleTree.emptyAny, AclRuleTree.emptyAny, Map.empty)
  private val updatedEndpoint = ResourceEndpointRecord(existingEndpointId, "/users/me", "GET", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true"))
  private val createdEndpoint = ResourceEndpointRecord(createdEndpointId, "/users", "POST", false, AclRuleTree.emptyAny, denyRules, Map.empty)
  private val resource = ResourceRecord(tenantId, resourceId, originalUri, Vector(existingEndpoint, removedEndpoint))
  private val otherTenantResource = ResourceRecord(otherTenantId, otherResourceId, ResourceUri("https://other.example.com"), Vector.empty)

  private val createRequest = CreateResourceRequest(
    tenantId = tenantId,
    resource = originalUri,
    endpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users", "GET", false, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", true, AclRuleTree.emptyAny, denyRules, Map.empty),
    ),
  )

  private val updateRequest = UpdateResourceRequest(
    id = resourceId,
    resource = Some(updatedUri),
    deleteEndpoints = Set(removedEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users/me", "GET", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", false, AclRuleTree.emptyAny, denyRules, Map.empty),
    ),
  )

  class Env(initial: Vector[ResourceRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ResourceRepository]
    val service = ResourceService.Impl(cache, repository)

  def spec = suite("ResourceService")(
    test("getTenantResources returns only tenant resources") {
      val env = new Env(Vector(resource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 0, limit = None)
      yield assertTrue(result == Vector(resource))
    },
    test("getTenantResources applies pagination after filtering") {
      val pagedResource = ResourceRecord(tenantId, ResourceId(3), updatedUri, Vector.empty)
      val env = new Env(Vector(resource, pagedResource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result == Vector(pagedResource))
    },
    test("createResource delegates resource and endpoint records to repository") {
      val env = new Env

      for
        _ <- env.repository.createResource.succeedsWith(resourceId)
        createdId <- env.service.createResource(createRequest)
      yield assertTrue(
        createdId == resourceId,
        env.repository.createResource.calls == List((
          tenantId,
          originalUri,
          Vector(
            ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
            ResourceEndpointRecord(createdEndpointId, "/users", "POST", true, AclRuleTree.emptyAny, denyRules, Map.empty),
          ),
        )),
      )
    },
    test("updateResource delegates patches and endpoint replacements to repository") {
      val env = new Env

      for
        _ <- env.repository.updateResource.succeedsWith(())
        _ <- env.service.updateResource(updateRequest)
      yield assertTrue(
        env.repository.updateResource.calls == List((
          resourceId,
          Some(updatedUri),
          Vector(updatedEndpoint, createdEndpoint),
          Set(removedEndpointId),
        )),
      )
    },
    test("deleteResource delegates id to repository") {
      val env = new Env

      for
        _ <- env.repository.deleteResource.succeedsWith(())
        _ <- env.service.deleteResource(resourceId)
      yield assertTrue(env.repository.deleteResource.calls == List(resourceId))
    },
    test("sync removes cached resource on delete event") {
      val env = new Env(Vector(resource, otherTenantResource))

      for
        _ <- env.service.sync(SyncEvent.ResourcesUpdated(tenantId, resourceId, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached == Vector(otherTenantResource))
    },
    test("sync upserts fetched resource for non-delete event") {
      val env = new Env(Vector(resource, otherTenantResource))
      val updatedResource = ResourceRecord(tenantId, resourceId, updatedUri, Vector(updatedEndpoint, createdEndpoint))

      for
        _ <- env.repository.findResource.succeedsWith(Some(updatedResource))
        _ <- env.service.sync(SyncEvent.ResourcesUpdated(tenantId, resourceId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findResource.calls == List(resourceId),
        cached == Vector(updatedResource, otherTenantResource),
      )
    },
    test("sync removes cached resource when record is missing on non-delete event") {
      val env = new Env(Vector(resource, otherTenantResource))

      for
        _ <- env.repository.findResource.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.ResourcesUpdated(tenantId, resourceId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findResource.calls == List(resourceId),
        cached == Vector(otherTenantResource),
      )
    },
  )