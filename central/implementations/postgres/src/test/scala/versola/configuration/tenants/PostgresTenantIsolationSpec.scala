package versola.configuration.tenants

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.tenants.TenantIsolationSpec
import versola.configuration.details.PostgresAuthorizationDetailTypeRepository
import versola.configuration.permissions.PostgresPermissionRepository
import versola.configuration.roles.PostgresRoleRepository
import versola.configuration.scopes.PostgresOAuthScopeRepository
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresTenantIsolationSpec extends PostgresSpec, TenantIsolationSpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield TenantIsolationSpec.Env(
        roles = PostgresRoleRepository(xa),
        permissions = PostgresPermissionRepository(xa),
        scopes = PostgresOAuthScopeRepository(xa),
        detailTypes = PostgresAuthorizationDetailTypeRepository(xa),
      )

  override def beforeEach(env: TenantIsolationSpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A'), ('tenant-b', 'Tenant B')".update.run())
    }.unit
