package versola.configuration.partners

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.partners.{PartnerApprovalRepositorySpec, PartnerId}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresPartnerApprovalRepositorySpec extends PostgresSpec, PartnerApprovalRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PartnerApprovalRepositorySpec.Env(
        PostgresPartnerApprovalRepository(xa),
        new PartnerApprovalRepositorySpec.Fixtures:
          def partner(id: PartnerId, tenantId: TenantId): Task[Unit] =
            xa.connect:
              sql"INSERT INTO tenants (id, description) VALUES ($tenantId, 'tenant') ON CONFLICT (id) DO NOTHING".update.run()
              sql"INSERT INTO edge_groups (id, kind) VALUES (${s"$id-group"}, 'partner')".update.run()
              sql"""
                INSERT INTO partners (id, tenant_id, display_name, logo_uri, contact, status, limits, group_id, console_client_id, settings, review_at, created_at)
                VALUES ($id, $tenantId, $id, NULL, '{"email":"ops@example.com"}'::jsonb, 'active', '{}'::jsonb, ${s"$id-group"}, ${s"$id-console"},
                        '{"defaults":{}}'::jsonb, NULL, now())
              """.update.run()
            .unit,
      )

  override def beforeEach(env: PartnerApprovalRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partners CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run())
    }.unit
