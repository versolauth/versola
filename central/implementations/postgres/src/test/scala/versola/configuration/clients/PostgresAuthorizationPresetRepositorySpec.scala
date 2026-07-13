package versola.configuration.clients

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.AuthorizationPresetRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresAuthorizationPresetRepositorySpec extends PostgresSpec, AuthorizationPresetRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AuthorizationPresetRepositorySpec.Env(PostgresAuthorizationPresetRepository(xa))

  override def beforeEach(env: AuthorizationPresetRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run()) *>
        xa.connect(sql"INSERT INTO otp_templates (id, tenant_id, localizations, purpose) VALUES ('default', 'tenant-a', '{}', 'otp')".update.run()) *>
        xa.connect(sql"INSERT INTO oauth_clients (tenant_id, id, client_name, redirect_uris, scope, external_audience, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id) VALUES ('tenant-a', 'client-1', 'Client 1', '{}', '{}', '{}', 300, 7776000, '{}', 'default', '{\"primary\":{\"credentials\":[\"phone\"],\"inlinePassword\":false,\"factors\":[{\"type\":\"otp\",\"required\":true}]}}'::jsonb, 'default')".update.run()) *>
        xa.connect(sql"INSERT INTO oauth_clients (tenant_id, id, client_name, redirect_uris, scope, external_audience, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id) VALUES ('tenant-a', 'client-2', 'Client 2', '{}', '{}', '{}', 300, 7776000, '{}', 'default', '{\"primary\":{\"credentials\":[\"phone\"],\"inlinePassword\":false,\"factors\":[{\"type\":\"otp\",\"required\":true}]}}'::jsonb, 'default')".update.run())
    }.unit
