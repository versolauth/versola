package versola.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.{AuthMethod, ClientId}
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind, EdgeRepositorySpec}
import versola.central.configuration.partners.{PartnerContact, PartnerId, PartnerLimits, PartnerRecord, PartnerSettings, PartnerStatus}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.configuration.partners.PostgresPartnerRepository
import versola.util.postgres.PostgresSpec
import zio.*

import java.time.Instant

object PostgresEdgeRepositorySpec extends PostgresSpec, EdgeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield EdgeRepositorySpec.Env(
        PostgresEdgeRepository(xa),
        new EdgeRepositorySpec.PartnerFixtures:
          override def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit] =
            xa.connect(sql"DELETE FROM partners WHERE group_id = $id".update.run()) *>
              xa.connect(sql"DELETE FROM edge_groups WHERE id = $id".update.run()) *>
              xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($id, ${kind.code})".update.run()).unit

          override def partner(groupId: EdgeGroupId, maxEdges: Int): Task[PartnerId] =
            val id = PartnerId(groupId)
            val record = PartnerRecord(
              id = id,
              tenantId = TenantId("tenant-a"),
              displayName = "ACME",
              logoUri = None,
              contact = PartnerContact("ops@acme.example", None),
              status = PartnerStatus.Active,
              limits = PartnerLimits(
                scopes = Set(ScopeToken("openid")),
                redirectDomains = Set("acme.example"),
                resourceDomains = None,
                maxClients = 5,
                maxEdges = maxEdges,
                accessTokenTtlMax = 600,
                refreshTokenTtlMax = 86400,
                clientAuthMethods = Set(AuthMethod.tls_client_auth),
              ),
              groupId = groupId,
              consoleClientId = PartnerRecord.consoleClientId(id),
              settings = PartnerSettings.empty,
              reviewAt = None,
              createdAt = Instant.parse("2026-11-10T10:00:00Z"),
              version = 0L,
            )
            xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A') ON CONFLICT (id) DO NOTHING".update.run()) *>
              PostgresPartnerRepository(xa).create(record).as(id),
      )

  override def beforeEach(env: EdgeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM partners WHERE id LIKE 'partner-%'".update.run())
    }.unit
