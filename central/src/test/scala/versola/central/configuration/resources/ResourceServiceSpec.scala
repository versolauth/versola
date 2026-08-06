package versola.central.configuration.resources

import org.scalamock.stubs.ZIOStubs
import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, InjectRule, InjectTarget, ResourceUri, UpdateResourceRequest}
import versola.util.{ReloadingCache, SecureRandom, Secret, SecurityService}
import versola.util.cel.CelEvaluator
import zio.*
import zio.test.*

import java.util.UUID

object ResourceServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val resourceId = ResourceId("users-api")
  private val otherResourceId = ResourceId("other-api")
  private val existingEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000401")
  private val removedEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000402")
  private val createdEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000403")
  private val originalUri = ResourceUri("https://api.example.com")
  private val updatedUri = ResourceUri("https://api.internal.example.com")
  private val allow = Some("token.role == 'admin' || token.department == 'support'")
  private val denyAware = Some("token.country != 'blocked'")
  private val inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub"))

  private val existingEndpoint = ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, None, Vector.empty, None, None, None)
  private val removedEndpoint = ResourceEndpointRecord(removedEndpointId, "/users", "DELETE", false, None, Vector.empty, None, None, None)
  private val updatedEndpoint = ResourceEndpointRecord(existingEndpointId, "/users/me", "GET", true, allow, inject, None, None, None)
  private val createdEndpoint = ResourceEndpointRecord(createdEndpointId, "/users", "POST", false, denyAware, Vector.empty, None, None, None)
  private val resource = ResourceRecord(tenantId, resourceId, originalUri, Vector(existingEndpoint, removedEndpoint))
  private val otherTenantResource = ResourceRecord(otherTenantId, otherResourceId, ResourceUri("https://other.example.com"), Vector.empty)

  private val createRequest = CreateResourceRequest(
    tenantId = tenantId,
    resourceId = resourceId,
    resource = originalUri,
    endpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users", "GET", false, allow, inject, stepUpCondition = None, stepUpAcr = None, maxAge = None),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", true, denyAware, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
    ),
    internal = false,
  )

  private val updateRequest = UpdateResourceRequest(
    resourceId = resourceId,
    resource = Some(updatedUri),
    deleteEndpoints = Set(removedEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(existingEndpointId, "/users/me", "GET", true, allow, inject, stepUpCondition = None, stepUpAcr = None, maxAge = None),
      CreateResourceEndpointRequest(createdEndpointId, "/users", "POST", false, denyAware, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
    ),
  )

  class Env(initial: Vector[ResourceRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ResourceRepository]
    val tenantRepository = stub[versola.central.configuration.tenants.TenantRepository]
    val celEvaluator = CelEvaluator.Impl(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty)))
    val secureRandom = stub[SecureRandom]
    val securityService = stub[SecurityService]
    val config = TestCentralConfig.config
    val service = ResourceService.Impl(cache, repository, tenantRepository, celEvaluator, secureRandom, securityService, config)

  def spec = suite("ResourceService")(
    test("getTenantResources returns only tenant resources") {
      val env = new Env(Vector(resource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 0, limit = None)
      yield assertTrue(result == Vector(resource))
    },
    test("getTenantResources applies pagination after filtering") {
      val pagedResource = ResourceRecord(tenantId, ResourceId("paged"), updatedUri, Vector.empty)
      val env = new Env(Vector(resource, pagedResource, otherTenantResource))

      for result <- env.service.getTenantResources(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result == Vector(pagedResource))
    },
    test("createResource delegates resource and endpoint records to repository") {
      val env = new Env

      for
        _ <- env.repository.createResource.succeedsWith(())
        result <- env.service.createResource(createRequest)
      yield assertTrue(
        result == Right((resourceId, None)),
        env.repository.createResource.calls == List((
          tenantId,
          resourceId,
          originalUri,
          Vector(
            ResourceEndpointRecord(existingEndpointId, "/users", "GET", false, allow, inject, None, None, None),
            ResourceEndpointRecord(createdEndpointId, "/users", "POST", true, denyAware, Vector.empty, None, None, None),
          ),
          None,
        )),
      )
    },
    test("createResource generates and encrypts a credential when internal is true") {
      val env = new Env
      val rawCredential = Array.fill(32)(7.toByte)
      val encryptedCredential = Array.fill(32)(8.toByte)

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(rawCredential)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedCredential)
        _ <- env.repository.createResource.succeedsWith(())
        result <- env.service.createResource(createRequest.copy(internal = true))
      yield assertTrue(
        result == Right((resourceId, Some(Secret(rawCredential)))),
        env.repository.createResource.calls.head._5 == Some(encryptedCredential),
      )
    },
    test("createResource returns error when allow expression is invalid CEL") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(existingEndpointId, "/users", "GET", false, Some("token.role =="), Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(())
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
          CreateResourceEndpointRequest(existingEndpointId, "/users/{id}", "GET", false, None, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
        ),
      )
      for result <- env.service.createResource(badRequest)
      yield assertTrue(
        result == Left(ResourceValidationError.InvalidEndpointPath(existingEndpointId)),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when endpoint path contains consecutive slashes") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(existingEndpointId, "/users//list", "GET", false, None, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
        ),
      )
      for result <- env.service.createResource(badRequest)
      yield assertTrue(
        result == Left(ResourceValidationError.InvalidEndpointPath(existingEndpointId)),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when endpoint path contains disallowed characters") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(existingEndpointId, "/users/:id", "GET", false, None, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
        ),
      )
      for result <- env.service.createResource(badRequest)
      yield assertTrue(
        result == Left(ResourceValidationError.InvalidEndpointPath(existingEndpointId)),
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
            stepUpCondition = None,
            stepUpAcr = None,
            maxAge = None,
          ),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(())
        result <- env.service.createResource(badRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_.isInstanceOf[ResourceValidationError.InvalidInjectExpression]),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when stepUpCondition expression is invalid CEL") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(
            existingEndpointId, "/users", "GET", false, allow,
            Vector.empty,
            stepUpCondition = Some("request.body.amount >"),
            stepUpAcr = None,
            maxAge = None,
          ),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(())
        result <- env.service.createResource(badRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_.isInstanceOf[ResourceValidationError.InvalidStepUpConditionExpression]),
        env.repository.createResource.calls.isEmpty,
      )
    },
    test("createResource returns error when stepUpCondition expression does not return boolean") {
      val env = new Env
      val badRequest = createRequest.copy(
        endpoints = Vector(
          CreateResourceEndpointRequest(
            existingEndpointId, "/users", "GET", false, allow,
            Vector.empty,
            stepUpCondition = Some("'mfa'"), // string instead of boolean
            stepUpAcr = None,
            maxAge = None,
          ),
        ),
      )

      for
        _ <- env.repository.createResource.succeedsWith(())
        result <- env.service.createResource(badRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_.isInstanceOf[ResourceValidationError.InvalidStepUpConditionExpression]),
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
    test("rotateCredential returns new secret and stores encrypted credential") {
      val env = new Env
      val rawCredential = Array.fill(32)(3.toByte)
      val encryptedCredential = Array.fill(32)(4.toByte)

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(rawCredential)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedCredential)
        _ <- env.repository.rotateCredential.succeedsWith(())
        result <- env.service.rotateCredential(resourceId)
        rotateCall = env.repository.rotateCredential.calls.head
      yield assertTrue(
        result == Secret(rawCredential),
        rotateCall._1 == resourceId,
        rotateCall._2.sameElements(encryptedCredential),
      )
    },
    test("deletePreviousCredential delegates id to repository") {
      val env = new Env

      for
        _ <- env.repository.deletePreviousCredential.succeedsWith(())
        _ <- env.service.deletePreviousCredential(resourceId)
      yield assertTrue(env.repository.deletePreviousCredential.calls == List(resourceId))
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