package versola.configuration.roles

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec.ListCodec
import versola.central.configuration.permissions.Permission
import versola.central.configuration.roles.{RoleId, RoleRecord, RoleRepository}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchDescription, PatchPermissions}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

class PostgresRoleRepository(
    xa: TransactorZIO,
) extends RoleRepository, BasicCodecs:
  given DbCodec[RoleId] = DbCodec.StringCodec.biMap(RoleId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[Permission] = DbCodec.StringCodec.biMap(Permission(_), identity[String])
  given DbCodec[RoleRecord] = DbCodec.derived[RoleRecord]

  override def getAll: Task[Vector[RoleRecord]] =
    xa.connect:
      sql"""SELECT id, tenant_id, description, permissions, active FROM roles"""
        .query[RoleRecord].run()

  override def findRole(
      tenantId: TenantId,
      id: RoleId,
  ): Task[Option[RoleRecord]] =
    xa.connect:
      getRole(id, tenantId).run().headOption


  override def createRole(
      tenantId: TenantId,
      id: RoleId,
      description: Map[String, String],
      permissions: List[Permission],
  ): Task[Unit] =
    xa.connect:
      sql"""
          INSERT INTO roles (id, tenant_id, description, permissions, active)
          VALUES ($id, $tenantId, $description, $permissions, TRUE)
        """.update.run()

  private def getRole(roleId: RoleId, tenantId: TenantId): Query[RoleRecord] =
    sql"""SELECT id, tenant_id, description, permissions, active FROM roles
          WHERE tenant_id = $tenantId AND id = $roleId"""
      .query[RoleRecord]

  override def updateRole(
      tenantId: TenantId,
      id: RoleId,
      descriptionPatch: PatchDescription,
      permissionsPatch: PatchPermissions,
  ): Task[Unit] =
    xa.repeatableRead.transact:
      getRole(id, tenantId).run().headOption match
        case None => ()
        case Some(role) =>
          val newDescr = (role.description -- descriptionPatch.delete) ++ descriptionPatch.add
          val newPermissions = (role.permissions -- permissionsPatch.remove) ++ permissionsPatch.add
          sql"""
               UPDATE roles SET
                 description = $newDescr,
                 permissions = $newPermissions
               WHERE
                 tenant_id = $tenantId AND
                 id = $id""".update.run()
          ()

  override def markRoleInactive(tenantId: TenantId, id: RoleId): Task[Unit] =
    Clock.instant.flatMap { now =>
      xa.connect:
        sql"""UPDATE roles SET active = FALSE WHERE tenant_id = $tenantId AND id = $id""".update.run()
      .unit
    }

  override def deleteRole(tenantId: TenantId, id: RoleId): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM roles WHERE tenant_id = $tenantId AND id = $id""".update.run()
    .unit

object PostgresRoleRepository:
  def live: ZLayer[TransactorZIO, Throwable, RoleRepository] =
    ZLayer.fromFunction(PostgresRoleRepository(_))
