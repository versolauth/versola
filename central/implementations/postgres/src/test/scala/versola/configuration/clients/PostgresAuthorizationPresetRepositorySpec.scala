package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
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
        xa.connect(
          sql"INSERT INTO otp_templates (id, tenant_id, localizations, purpose, channel) VALUES ('default', 'tenant-a', '{}', 'otp', 'sms')".update.run(),
        ) *>
        xa.connect(
          sql"INSERT INTO oauth_clients (tenant_id, id, client_name, redirect_uris, scope, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, auth_method, front_channel_logout_session_required, certificate_bound_access_tokens, dpop_bound_access_tokens, require_signed_request_object, require_pushed_authorization_requests, dpop_signing_algs, created_at, application_type) VALUES ('tenant-a', 'client-1', '{\"en\":\"Client 1\"}', '{}', '{}', 300, 7776000, '{}', 'default', '{\"primary\":{\"credentials\":[\"phone\"],\"inlinePassword\":false,\"factors\":[{\"type\":\"otp\",\"required\":true}]}}'::jsonb, 'default', 'client_secret', false, false, false, false, false, '{}', now(), 'web')".update.run(),
        ) *>
        xa.connect(
          sql"INSERT INTO oauth_clients (tenant_id, id, client_name, redirect_uris, scope, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, auth_method, front_channel_logout_session_required, certificate_bound_access_tokens, dpop_bound_access_tokens, require_signed_request_object, require_pushed_authorization_requests, dpop_signing_algs, created_at, application_type) VALUES ('tenant-a', 'client-2', '{\"en\":\"Client 2\"}', '{}', '{}', 300, 7776000, '{}', 'default', '{\"primary\":{\"credentials\":[\"phone\"],\"inlinePassword\":false,\"factors\":[{\"type\":\"otp\",\"required\":true}]}}'::jsonb, 'default', 'client_secret', false, false, false, false, false, '{}', now(), 'web')".update.run(),
        )
    }.unit
