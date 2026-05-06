package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserId
import zio.{Task, ZLayer}

import java.util.UUID

class PostgresUserRolesRepository(xa: TransactorZIO) extends UserRolesRepository:

  override def findRolesByUser(userId: UserId): Task[List[RoleId]] =
    xa.connect:
      sql"SELECT role_id FROM user_roles WHERE user_id = $userId"
        .query[String]
        .run()
        .map(RoleId(_))
        .toList

  override def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]] =
    xa.connect:
      sql"SELECT role_id FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId"
        .query[String]
        .run()
        .map(RoleId(_))
        .toList

  override def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] =
    xa.connect:
      sql"INSERT INTO user_roles (user_id, tenant_id, role_id) VALUES ($userId, $tenantId, $roleId) ON CONFLICT DO NOTHING"
        .update.run()
    .unit

  override def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] =
    xa.connect:
      sql"DELETE FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId AND role_id = $roleId"
        .update.run()
    .unit

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[RoleId] = DbCodec.StringCodec.biMap(RoleId(_), identity[String])

object PostgresUserRolesRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserRolesRepository] =
    ZLayer.fromFunction(PostgresUserRolesRepository(_))
