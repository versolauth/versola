package versola.configuration.permissions

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.PatchDescription
import versola.central.configuration.permissions.{Permission, PermissionRecord, PermissionRepository}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresPermissionRepository(xa: TransactorZIO) extends PermissionRepository, BasicCodecs:

  given DbCodec[Permission] = DbCodec.StringCodec.biMap(Permission(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ResourceEndpointId] = DbCodec.UUIDCodec.biMap(ResourceEndpointId(_), identity)

  given DbCodec[PermissionRecord] = DbCodec.derived

  override def getAll: Task[Vector[PermissionRecord]] =
    xa.connect:
      sql"""
        SELECT tenant_id, id, description, endpoint_ids FROM permissions
        ORDER BY tenant_id NULLS FIRST, id
      """.query[PermissionRecord].run()

  override def findPermission(
    tenantId: Option[TenantId],
    permission: Permission
  ): Task[Option[PermissionRecord]] =
    xa.connect:
      sql"""
        SELECT tenant_id, id, description, endpoint_ids FROM permissions
        WHERE tenant_id IS NOT DISTINCT FROM $tenantId AND id = $permission
      """.query[PermissionRecord].run().headOption

  override def createPermission(
      tenantId: Option[TenantId],
      permission: Permission,
      description: Map[String, String],
      endpointIds: Set[ResourceEndpointId],
  ): Task[Unit] =
    xa.connect:
      sql"""INSERT INTO permissions (tenant_id, id, description, endpoint_ids)
            VALUES ($tenantId, $permission, $description, $endpointIds)""".update.run()
    .unit

  override def updatePermission(
      tenantId: Option[TenantId],
      permission: Permission,
      descriptionPatch: PatchDescription,
      endpointIdsPatch: Option[Set[ResourceEndpointId]],
  ): Task[Unit] =
    xa.repeatableRead.transact:
      val existing = sql"""
        SELECT tenant_id, id, description, endpoint_ids FROM permissions
        WHERE tenant_id IS NOT DISTINCT FROM $tenantId AND id = $permission
      """.query[PermissionRecord].run().headOption

      existing match
        case None => ()
        case Some(perm) =>
          val newDescription = descriptionPatch.patch(perm.description)
          val newEndpointIds = endpointIdsPatch.getOrElse(perm.endpointIds)
          sql"""
            UPDATE permissions
            SET description = $newDescription,
                endpoint_ids = $newEndpointIds
            WHERE tenant_id IS NOT DISTINCT FROM $tenantId AND id = $permission
          """.update.run()
          ()
    .unit

  override def deletePermission(
      tenantId: Option[TenantId],
      permission: Permission,
  ): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM permissions WHERE tenant_id IS NOT DISTINCT FROM $tenantId AND id = $permission""".update.run()
    .unit

object PostgresPermissionRepository:
  def live: ZLayer[TransactorZIO, Throwable, PermissionRepository] =
    ZLayer.fromFunction(PostgresPermissionRepository(_))
