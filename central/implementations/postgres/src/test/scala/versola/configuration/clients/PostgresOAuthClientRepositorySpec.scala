package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.{AuthMethod, ClientId, OAuthClientRepositorySpec}
import versola.central.configuration.edges.EdgeGroupId
import versola.central.configuration.partners.{PartnerContact, PartnerId, PartnerLimits, PartnerRecord, PartnerSettings, PartnerStatus}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.configuration.partners.PostgresPartnerRepository
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

object PostgresOAuthClientRepositorySpec extends PostgresSpec, OAuthClientRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield OAuthClientRepositorySpec.Env(
        PostgresOAuthClientRepository(xa),
        new OAuthClientRepositorySpec.PartnerFixtures:
          override def partner(id: PartnerId, maxClients: Int): Task[ClientId] =
            val group = EdgeGroupId(s"partner-limit-$id")
            val limits = PartnerLimits(
              scopes = Set(ScopeToken("openid")),
              redirectDomains = Set("acme.example"),
              resourceDomains = None,
              maxClients = maxClients,
              maxEdges = 2,
              accessTokenTtlMax = 600,
              refreshTokenTtlMax = 86400,
              clientAuthMethods = Set(AuthMethod.tls_client_auth),
              rateLimitPolicy = "partner-default",
            )
            val record = PartnerRecord(
              id = id,
              tenantId = TenantId("tenant-a"),
              displayName = "ACME",
              logoUri = None,
              contact = PartnerContact("ops@acme.example", None),
              status = PartnerStatus.Active,
              limits = limits,
              groupId = group,
              consoleClientId = PartnerRecord.consoleClientId(id),
              settings = PartnerSettings.empty,
              reviewAt = None,
              createdAt = Instant.parse("2026-11-10T10:00:00Z"),
              version = 0L,
            )
            // the tenants were truncated, and with them the partners: what is left is the group a previous run made
            xa.connect(sql"DELETE FROM edge_groups WHERE id = $group".update.run()) *>
              xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($group, 'partner')".update.run()) *>
              PostgresPartnerRepository(xa).create(record).as(record.consoleClientId),
      )

  override def beforeEach(env: OAuthClientRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
