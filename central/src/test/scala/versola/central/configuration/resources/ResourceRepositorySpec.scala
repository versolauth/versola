package versola.central.configuration.resources

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.{InjectRule, InjectTarget, ResourceUri}
import versola.central.configuration.clients.ClientId
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
  val audience = List(ClientId("test-client"))
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
    stepUpCondition = None,
    stepUpAcr = None,
    maxAge = None,
  )

  def resourceRecord(
      id: ResourceId = resourceId,
      resource: ResourceUri = resourceUri,
      resourceAudience: List[ClientId] = audience,
      endpoints: Vector[ResourceEndpointRecord] = Vector.empty,
      secret: Option[versola.util.Secret] = None,
      previousSecret: Option[versola.util.Secret] = None,
  ) =
    ResourceRecord(
      tenantId = tenantId,
      resourceId = id,
      resource = resource,
      audience = resourceAudience,
      endpoints = endpoints,
      secret = secret,
      previousSecret = previousSecret,
    )

  override def testCases(env: ResourceRepositorySpec.Env) =
    List(
      test("create and find resource") {
        for
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, audience, Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)), None)
          found <- env.resourceRepository.findResource(resourceId)
          all <- env.resourceRepository.getAll
        yield assertTrue(
          found == Some(resourceRecord(resourceId, endpoints = Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)))),
          all == Vector(resourceRecord(resourceId, endpoints = Vector(endpointRecord(usersListEndpointId, allow = allow, inject = inject)))),
        )
      },
      test("create resource with secret") {
        val secret = Array.fill(32)(9.toByte)
        for
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, audience, Vector.empty, Some(secret))
          found <- env.resourceRepository.findResource(resourceId)
        yield assertTrue(found.map(_.secret.map(_.toVector)) == Some(Some(secret.toVector)))
      },
      test("rotate and delete previous secret") {
        val secret1 = Array.fill(32)(1.toByte)
        val secret2 = Array.fill(32)(2.toByte)
        for
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, audience, Vector.empty, Some(secret1))
          _ <- env.resourceRepository.rotateSecret(resourceId, secret2)
          afterRotate <- env.resourceRepository.findResource(resourceId)
          _ <- env.resourceRepository.deletePreviousSecret(resourceId)
          afterDelete <- env.resourceRepository.findResource(resourceId)
        yield assertTrue(
          afterRotate.map(_.secret.map(_.toVector)) == Some(Some(secret2.toVector)),
          afterRotate.map(_.previousSecret.map(_.toVector)) == Some(Some(secret1.toVector)),
          afterDelete.flatMap(_.previousSecret) == None,
        )
      },
      test("update resource fields and embedded endpoints") {
        for
          _ <- env.resourceRepository.createResource(
            tenantId,
            resourceId,
            resourceUri,
            audience,
            Vector(endpointRecord(usersListEndpointId), endpointRecord(usersDeleteEndpointId, method = "DELETE")),
            None,
          )
          _ <- env.resourceRepository.updateResource(
            resourceId = resourceId,
            resourcePatch = Some(ResourceUri("https://api.internal.example.com")),
            audiencePatch = Some(List(ClientId("updated-client"))),
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
              resourceAudience = List(ClientId("updated-client")),
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
          _ <- env.resourceRepository.createResource(tenantId, resourceId, resourceUri, audience, Vector(endpointRecord(usersListEndpointId)), None)
          _ <- env.resourceRepository.deleteResource(resourceId)
          found <- env.resourceRepository.findResource(resourceId)
        yield assertTrue(found.isEmpty)
      },
    )

object ResourceRepositorySpec:
  case class Env(resourceRepository: ResourceRepository)