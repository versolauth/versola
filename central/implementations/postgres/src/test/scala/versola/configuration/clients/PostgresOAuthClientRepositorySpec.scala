package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.{AuthMethod, ClientAlreadyExists, ClientApproval, ClientId, OAuthClientRepositorySpec}
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
      yield OAuthClientRepositorySpec.Env(PostgresOAuthClientRepository(xa))

  override def beforeEach(env: OAuthClientRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit

  /** #962 review: the limits of a partner are the table's to enforce, where two creations at `max - 1` cannot both pass. */
  override def implementationCases(env: OAuthClientRepositorySpec.Env) =
    List(
      test("a partner's clients are as many as its limit and no more, however many are created at once, and its console is not counted") {
        for
          xa <- ZIO.service[TransactorZIO]
          partners = PostgresPartnerRepository(xa)
          group = EdgeGroupId("partner-limit-acme")
          // the tenants were truncated, and with them the partners: what is left is the group a previous run made
          _ <- xa.connect(sql"DELETE FROM edge_groups WHERE id = $group".update.run())
          _ <- xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($group, 'partner')".update.run())
          acme = partnerRecord(group).copy(limits = partnerRecord(group).limits.copy(maxClients = 2))
          _ <- partners.create(acme)
          owned = (n: Int) => client.copy(id = ClientId(s"acme-$n"), partnerId = Some(acme.id), approval = ClientApproval.Pending)
          results <- ZIO.foreachPar(1 to 8)(n => env.repository.createClient(owned(n)).either)
          stored <- xa.connect(sql"SELECT COUNT(*) FROM oauth_clients WHERE partner_id = ${acme.id}".query[Long].run().head)
          // the console is registered with the partner, whatever is full
          console <- env.repository.createClient(client.copy(id = acme.consoleClientId, partnerId = Some(acme.id))).either
          again <- env.repository.createClient(owned(99)).either
          storedAfter <- xa.connect(sql"SELECT COUNT(*) FROM oauth_clients WHERE partner_id = ${acme.id}".query[Long].run().head)
          // a first-party client has no limit
          free <- ZIO.foreach(1 to 4)(n => env.repository.createClient(client.copy(id = ClientId(s"own-$n"))).either)
          _ <- xa.connect(sql"DELETE FROM oauth_clients WHERE partner_id = ${acme.id}".update.run())
          _ <- xa.connect(sql"DELETE FROM partners WHERE id = ${acme.id}".update.run())
          _ <- xa.connect(sql"DELETE FROM edge_groups WHERE id = $group".update.run())
        yield assertTrue(
          results.count(_.isRight) == 2,
          results.collect { case Left(error: Throwable) => error.getMessage }.forall(_.contains("partner_client_limit")),
          results.forall:
            case Left(_: ClientAlreadyExists) => false
            case _ => true,
          stored == 2L,
          console.isRight,
          again.isLeft,
          storedAfter == 3L,
          free.forall(_.isRight),
        )
      },
    )

  private def partnerRecord(group: EdgeGroupId): PartnerRecord =
    PartnerRecord(
      id = PartnerId("acme"),
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
        maxEdges = 2,
        accessTokenTtlMax = 600,
        refreshTokenTtlMax = 86400,
        clientAuthMethods = Set(AuthMethod.tls_client_auth),
        rateLimitPolicy = "partner-default",
      ),
      groupId = group,
      consoleClientId = ClientId("acme-console"),
      settings = PartnerSettings.empty,
      reviewAt = None,
      createdAt = Instant.parse("2026-11-10T10:00:00Z"),
      version = 0L,
    )
