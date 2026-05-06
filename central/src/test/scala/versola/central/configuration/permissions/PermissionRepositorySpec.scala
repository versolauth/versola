package versola.central.configuration.permissions

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.PatchDescription
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.prelude.EqualOps
import zio.test.*

import java.util.UUID

trait PermissionRepositorySpec extends DatabaseSpecBase[PermissionRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  val tenantId = TenantId("tenant-a")
  val permissionId = Permission("users:read")
  val listEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000101")
  val detailEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000102")
  val permissionRecord = PermissionRecord(
    tenantId = Some(tenantId),
    id = permissionId,
    description = Map("en" -> "Read users"),
    endpointIds = Set(listEndpointId),
  )

  override def testCases(env: PermissionRepositorySpec.Env) =
    List(
      test("create and find tenant permission") {
        for
          _ <- env.repository.createPermission(Some(tenantId), permissionId, Map("en" -> "Read users"), permissionRecord.endpointIds)
          found <- env.repository.findPermission(Some(tenantId), permissionId)
          all <- env.repository.getAll
        yield assertTrue(
          found === Some(permissionRecord),
          all === Vector(permissionRecord),
        )
      },
      test("update permission description with patch") {
        for
          _ <- env.repository.createPermission(
            Some(tenantId),
            permissionId,
            Map("en" -> "Read users", "de" -> "Benutzer lesen"),
            permissionRecord.endpointIds,
          )
          _ <- env.repository.updatePermission(
            Some(tenantId),
            permissionId,
            PatchDescription(
              add = Map("ru" -> "Чтение пользователей"),
              delete = Set("de"),
            ),
            Some(Set(listEndpointId, detailEndpointId)),
          )
          found <- env.repository.findPermission(Some(tenantId), permissionId)
        yield assertTrue(
          found === Some(
            PermissionRecord(
              tenantId = Some(tenantId),
              id = permissionId,
              description = Map("en" -> "Read users", "ru" -> "Чтение пользователей"),
              endpointIds = Set(listEndpointId, detailEndpointId),
            )
          )
        )
      },
      test("delete permission") {
        for
          _ <- env.repository.createPermission(Some(tenantId), permissionId, Map("en" -> "Read users"), permissionRecord.endpointIds)
          _ <- env.repository.deletePermission(Some(tenantId), permissionId)
          found <- env.repository.findPermission(Some(tenantId), permissionId)
        yield assertTrue(found.isEmpty)
      },
    )

object PermissionRepositorySpec:
  case class Env(repository: PermissionRepository)
