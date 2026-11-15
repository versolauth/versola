package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.certificates.CertificateExpiryRepositorySpec
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind}
import versola.configuration.edges.PostgresEdgeCertificateRepository
import versola.configuration.partners.PostgresPartnerRepository
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresCertificateExpiryRepositorySpec extends PostgresSpec, CertificateExpiryRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield CertificateExpiryRepositorySpec.Env(
        issuances = PostgresClientCertificateIssuanceRepository(xa),
        edgeCertificates = PostgresEdgeCertificateRepository(xa),
        est = PostgresClientEstRepository(xa),
        clients = PostgresOAuthClientRepository(xa),
        partners = PostgresPartnerRepository(xa),
        fixtures = new CertificateExpiryRepositorySpec.Fixtures:
          def tenant: Task[Unit] =
            xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run()) *>
              xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A') ON CONFLICT (id) DO NOTHING".update.run()).unit
          def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit] =
            xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($id, ${kind.code})".update.run()).unit
          def edge(id: String, group: EdgeGroupId): Task[Unit] =
            xa.connect(sql"INSERT INTO edges (id, require_dpop_nonce, group_id) VALUES ($id, true, $group)".update.run()).unit,
      )

  override def beforeEach(env: CertificateExpiryRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run())
    }.unit
