package versola.central.configuration.resources

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.{AclRuleTree, PermissionRule, ResourceUri}
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

trait ResourceRepositorySpec extends DatabaseSpecBase[ResourceRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  val tenantId = TenantId("tenant-a")
  val resourceUri = ResourceUri("https://api.example.com")
  val usersListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000501")
  val usersDeleteEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000502")
  val usersMeEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000503")
  val usersCreateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000504")
  val allowRules = AclRuleTree.any(Vector(
    AclRuleTree.all(Vector(AclRuleTree.rule(PermissionRule("role", "equals", Json.Str("admin"))))),
    AclRuleTree.all(Vector(AclRuleTree.rule(PermissionRule("department", "equals", Json.Str("support"))))),
  ))
  val denyRules = AclRuleTree.any(Vector(AclRuleTree.all(Vector(AclRuleTree.rule(PermissionRule("country", "equals", Json.Str("blocked")))))))

  def endpointRecord(
      endpointId: ResourceEndpointId,
      method: String = "GET",
      path: String = "/users",
      fetchUserInfo: Boolean = false,
      allowRules: AclRuleTree = AclRuleTree.emptyAny,
      denyRules: AclRuleTree = AclRuleTree.emptyAny,
      injectHeaders: Map[String, String] = Map.empty,
  ) = ResourceEndpointRecord(
    id = endpointId,
    path = path,
    method = method,
    fetchUserInfo = fetchUserInfo,
    allowRules = allowRules,
    denyRules = denyRules,
    injectHeaders = injectHeaders,
  )

  def resourceRecord(id: ResourceId, resource: ResourceUri = resourceUri, endpoints: Vector[ResourceEndpointRecord] = Vector.empty) =
    ResourceRecord(
      tenantId = tenantId,
      id = id,
      resource = resource,
      endpoints = endpoints,
    )

  override def testCases(env: ResourceRepositorySpec.Env) =
    List(
      test("create and find resource") {
        for
          createdId <- env.resourceRepository.createResource(tenantId, resourceUri, Vector(endpointRecord(usersListEndpointId, allowRules = allowRules, denyRules = denyRules)))
          found <- env.resourceRepository.findResource(createdId)
          all <- env.resourceRepository.getAll
        yield assertTrue(
          found == Some(resourceRecord(createdId, endpoints = Vector(endpointRecord(usersListEndpointId, allowRules = allowRules, denyRules = denyRules)))),
          all == Vector(resourceRecord(createdId, endpoints = Vector(endpointRecord(usersListEndpointId, allowRules = allowRules, denyRules = denyRules)))),
        )
      },
      test("update resource fields and embedded endpoints") {
        for
          createdId <- env.resourceRepository.createResource(
            tenantId,
            resourceUri,
            Vector(endpointRecord(usersListEndpointId), endpointRecord(usersDeleteEndpointId, method = "DELETE")),
          )
          _ <- env.resourceRepository.updateResource(
            id = createdId,
            resourcePatch = Some(ResourceUri("https://api.internal.example.com")),
            addEndpoints = Vector(
              endpointRecord(usersMeEndpointId, path = "/users/me", fetchUserInfo = true, injectHeaders = Map("X-Trace" -> "enabled")),
              endpointRecord(usersCreateEndpointId, method = "POST"),
            ),
            deleteEndpoints = Set(usersListEndpointId, usersDeleteEndpointId),
          )
          found <- env.resourceRepository.findResource(createdId)
        yield assertTrue(
          found == Some(
            resourceRecord(
              createdId,
              resource = ResourceUri("https://api.internal.example.com"),
              endpoints = Vector(
                endpointRecord(usersMeEndpointId, path = "/users/me", fetchUserInfo = true, injectHeaders = Map("X-Trace" -> "enabled")),
                endpointRecord(usersCreateEndpointId, method = "POST"),
              ),
            )
          ),
        )
      },
      test("delete resource") {
        for
          createdId <- env.resourceRepository.createResource(tenantId, resourceUri, Vector(endpointRecord(usersListEndpointId)))
          _ <- env.resourceRepository.deleteResource(createdId)
          found <- env.resourceRepository.findResource(createdId)
        yield assertTrue(found.isEmpty)
      },
    )

object ResourceRepositorySpec:
  case class Env(resourceRepository: ResourceRepository)