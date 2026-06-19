package versola.central.configuration.resources

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, InjectRule, InjectTarget, ResourceUri, UpdateResourceRequest}
import versola.util.ReloadingCache
import versola.util.cel.CelEvaluator
import zio.*
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
  private val allow = Some("token.role == 'admin' || token.department == 'support'")
  private val denyAware = Some("token.country != 'blocked'")
  private val inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub"))

  private val existingEndpoint = ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, None, Vector.empty)
  private val removedEndpoint = ResourceEndpointRecord(removedEndpointId, "/users", "DELETE", false, None, Vector.empty)
  private val updatedEndpoint = ResourceEndpointRecord(existingEndpointId, "/users/me", "GET", true, allow, inject)
  private val createdEndpoint = ResourceEndpointRecord(createdEndpointId, "/users", "POST", false, denyAware, Vector.empty)
  private val originalAlias = "users-api"
  private val updatedAlias = "users-internal"
  private val resource = ResourceRecord(tenantId, resourceId, originalAlias, originalUri, Vector(existingEndpoint, removedEndpoint))
  private val otherTenantResource = ResourceRecord(otherTenantId, otherResourceId, "other-api", ResourceUri("https://other.example.com"), Vector.empty)

  private val createRequest = CreateResourceRequest(
    tenantId = tenantId,
    alias = originalAlias,
    resource = originalUri,
    endpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users", "GET", false, allow, inject),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", true, denyAware, Vector.empty),
    ),
  )

  private val updateRequest = UpdateResourceRequest(
    id = resourceId,
    alias = Some(updatedAlias),
    resource = Some(updatedUri),
    deleteEndpoints = Set(removedEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users/me", "GET", true, allow, inject),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", false, denyAware, Vector.empty),
    ),
  )

  class Env(initial: Vector[ResourceRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ResourceRepository]
    val tenantRepository = stub[versola.central.configuration.tenants.TenantRepository]
    val celEvaluator = CelEvaluator.Impl(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty)))
    val service = ResourceService.Impl(cache, repository, tenantRepository, celEvaluator)

  def spec = suite("ResourceService")(
    test("getTenantResources returns only tenant resources") {
      val env = new Env(Vector(resource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 0, limit = None)
      yield assertTrue(result == Vector(resource))
    },
    test("getTenantResources applies pagination after filtering") {
      val pagedResource = ResourceRecord(tenantId, ResourceId(3), "paged", updatedUri, Vector.empty)
      val env = new Env(Vector(resource, pagedResource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result == Vector(pagedResource))
    },
    test("createResource delegates resource and endpoint records to repository") {
      val env = new Env

      for
        _ <- env.repository.createResource.succeedsWith(resourceId)
        result <- env.service.createResource(createRequest)
      yield assertTrue(
        result == Right(resourceId),
        env.repository.createResource.calls == List((
          tenantId,
          originalAlias,
          originalUri,
          Vector(
            ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, allow, inject),
            ResourceEndpointRecord(createdEndpointId, "/users", "POST", true, denyAware, Vector.empty),
          ),
        )),
      )
    },
    test("createResource returns error when allow expression is invalid CEL") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(existingEndpointId, "/users", "GET", false, Some("token.role =="), Vector.empty),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(resourceId)
        result <- env.service.createResource(badRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_.isInstanceOf[ResourceValidationError.InvalidAllowExpression]),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when endpoint path contains path parameters") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(existingEndpointId, "/users/{id}", "GET", false, None, Vector.empty),
        ),
      )
      for result <- env.service.createResource(badRequest)
      yield assertTrue(
        result == Left(ResourceValidationError.PathParametersNotAllowed(existingEndpointId)),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when inject expression is invalid CEL") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(
            existingEndpointId, "/users", "GET", false, allow,
            Vector(InjectRule(InjectTarget.header, "x-bad", "(unterminated")),
          ),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(resourceId)
        result <- env.service.createResource(badRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_.isInstanceOf[ResourceValidationError.InvalidInjectExpression]),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("updateResource delegates patches and endpoint replacements to repository") {
      val env = new Env

      for
        _ <- env.repository.updateResource.succeedsWith(())
        result <- env.service.updateResource(updateRequest)
      yield assertTrue(
        result == Right(()),
        env.repository.updateResource.calls == List((
          resourceId,
          Some(updatedAlias),
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
      val updatedResource = ResourceRecord(tenantId, resourceId, updatedAlias, updatedUri, Vector(updatedEndpoint, createdEndpoint))

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