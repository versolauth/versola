package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZIO, ZLayer}

import java.util.UUID

class PostgresUserRolesRepository(xa: TransactorZIO) extends UserRolesRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[RoleId] = DbCodec.StringCodec.biMap(RoleId(_), identity[String])

  override def findRolesByUser(userId: UserId): Task[List[RoleId]] =
    xa.connectMeasured("find-roles-by-user"):
      sql"SELECT role_id FROM user_roles WHERE user_id = $userId"
        .query[String]
        .run()
        .map(RoleId(_))
        .toList

  override def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]] =
    xa.connectMeasured("find-roles-by-user-and-tenant"):
      sql"SELECT role_id FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId"
        .query[String]
        .run()
        .map(RoleId(_))
        .toList

  override def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit] =
    if add.isEmpty && remove.isEmpty then ZIO.unit
    else
      xa.transactMeasured("update-roles"):
        if remove.nonEmpty then
          remove.foreach: roleId =>
            sql"DELETE FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId AND role_id = $roleId"
              .update.run()
        if add.nonEmpty then
          add.foreach: roleId =>
            sql"INSERT INTO user_roles (user_id, tenant_id, role_id) VALUES ($userId, $tenantId, $roleId) ON CONFLICT DO NOTHING"
              .update.run()
      .unit

object PostgresUserRolesRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserRolesRepository] =
    ZLayer.fromFunction(PostgresUserRolesRepository(_))
