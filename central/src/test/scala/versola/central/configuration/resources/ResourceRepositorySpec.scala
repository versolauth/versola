package versola.central.configuration.resources

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.{InjectRule, InjectTarget, ResourceUri}
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.test.*

import java.util.UUID

trait ResourceRepositorySpec extends DatabaseSpecBase[ResourceRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  val tenantId = TenantId("tenant-a")
  val resourceId = ResourceId("users-api")
  val resourceUri = ResourceUri("https://api.example.com")
  val usersListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000501")
  val usersDeleteEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000502")
  val usersMeEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000503")
  val usersCreateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000504")
  val allow = Some("token.role == 'admin' || token.department == 'support'")
  val inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub"))

  def endpointRecord(
      endpointId: ResourceEndpointId,
      method: String = "GET",
      path: String = "/users",
      fetchUserInfo: Boolean = false,
      allow: Option[String] = None,
      inject: Vector[InjectRule] = Vector.empty,
  ) = ResourceEndpointRecord(
    id = endpointId,
    path = path,
    method = method,
    fetchUserInfo = fetchUserInfo,
    allowExpression = allow,
    inject = inject,
  )

  def resourceRecord(
      id: ResourceId = resourceId,
      resource: ResourceUri = resourceUri,
      endpoints: Vector[ResourceEndpointRecord] = Vector.empty,
  ) =
    ResourceRecord(
      tenantId = tenantId,
      resourceId = id,
      resource = resource,
      endpoints = endpoints,
    )

  override def testCases(env: ResourceRepositorySpec.Env) =
    List(
      test("create and find resource") {
        for
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)))
          found <- env.resourceRepository.findResource(resourceId)
          all <- env.resourceRepository.getAll
        yield assertTrue(
          found == Some(resourceRecord(resourceId, endpoints = Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)))),
          all == Vector(resourceRecord(resourceId, endpoints = Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)))),
        )
      },
      test("update resource fields and embedded endpoints") {
        for
          _ <- env.resourceRepository.createResource(
            tenantId,
            resourceId,
            resourceUri,
            Vector(endpointRecord(usersListEndpointId), endpointRecord(usersDeleteEndpointId, method = "DELETE")),
          )
          _ <- env.resourceRepository.updateResource(
            resourceId = resourceId,
            resourcePatch = Some(ResourceUri("https://api.internal.example.com")),
            addEndpoints = Vector(
              endpointRecord(usersMeEndpointId, path = "/users/me", fetchUserInfo = true, inject = Vector(InjectRule(InjectTarget.header, "X-Trace", "'enabled'"))),
              endpointRecord(usersCreateEndpointId, method = "POST"),
            ),
            deleteEndpoints = Set(usersListEndpointId, usersDeleteEndpointId),
          )
          found <- env.resourceRepository.findResource(resourceId)
        yield assertTrue(
          found == Some(
            resourceRecord(
              resourceId,
              resource = ResourceUri("https://api.internal.example.com"),
              endpoints = Vector(
                endpointRecord(usersMeEndpointId, path = "/users/me", fetchUserInfo = true, inject = Vector(InjectRule(InjectTarget.header, "X-Trace", "'enabled'"))),
                endpointRecord(usersCreateEndpointId, method = "POST"),
              ),
            )
          ),
        )
      },
      test("delete resource") {
        for
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, Vector(endpointRecord(usersListEndpointId)))
          _ <- env.resourceRepository.deleteResource(resourceId)
          found <- env.resourceRepository.findResource(resourceId)
        yield assertTrue(found.isEmpty)
      },
    )

object ResourceRepositorySpec:
  case class Env(resourceRepository: ResourceRepository)