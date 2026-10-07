package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.*
import versola.central.configuration.clients.certificates.ClientCertificateIssuance
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

object PostgresClientCertificateIssuanceRepositorySpec extends PostgresSpec:

  private val clientId = ClientId("mobile-app")
  private val other = ClientId("other-app")
  private val t0 = Instant.parse("2026-03-01T00:00:00Z")

  private def record(id: ClientId) = OAuthClientRecord(
    id = id,
    tenantId = TenantId("tenant-a"),
    clientName = Map("en" -> "App"),
    redirectUris = Set(RedirectUri("https://example.com/callback")),
    scope = Set.empty,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set.empty,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.none,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = None,
    createdAt = Instant.EPOCH,
  )

  private val setup =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run())
      _ <- xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run())
      _ <- xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
      clients = PostgresOAuthClientRepository(xa)
      _ <- clients.createClient(record(clientId))
      _ <- clients.createClient(record(other))
    yield (xa, clients, PostgresClientCertificateIssuanceRepository(xa))

  def spec = suite("PostgresClientCertificateIssuanceRepository")(
    test("upsert records an issuance and a second one replaces it") {
      for
        (_, _, repository) <- setup
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0.plusSeconds(100), t0))
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "02", t0.plusSeconds(500), t0.plusSeconds(10)))
        found <- repository.find(clientId)
      yield assertTrue(found.map(_.serial) == Some("02"), found.map(_.notAfter) == Some(t0.plusSeconds(500)))
    },
    test("expiringBefore returns only the issuances inside the window, soonest first") {
      for
        (_, _, repository) <- setup
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0.plusSeconds(500), t0))
        _ <- repository.upsert(ClientCertificateIssuance(other, "02", t0.plusSeconds(100), t0))
        due <- repository.expiringBefore(t0.plusSeconds(300))
        all <- repository.expiringBefore(t0.plusSeconds(1000))
      yield assertTrue(due.map(_.clientId) == Vector(other), all.map(_.clientId) == Vector(other, clientId))
    },
    test("delete forgets an issuance") {
      for
        (_, _, repository) <- setup
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
        _ <- repository.delete(clientId)
        found <- repository.find(clientId)
      yield assertTrue(found.isEmpty)
    },
    test("replace updates an existing issuance and never creates one") {
      for
        (_, _, repository) <- setup
        missing <- repository.replace(ClientCertificateIssuance(clientId, "09", t0, t0))
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
        replaced <- repository.replace(ClientCertificateIssuance(clientId, "02", t0.plusSeconds(60), t0))
        found <- repository.find(clientId)
      yield assertTrue(!missing, replaced, found.map(_.serial) == Some("02"))
    },
    test("deleting the client removes its issuance") {
      for
        (_, clients, repository) <- setup
        _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
        _ <- clients.deleteClient(clientId)
        found <- repository.find(clientId)
      yield assertTrue(found.isEmpty)
    },
    test("an issuance cannot exist for a client that does not") {
      for
        (_, _, repository) <- setup
        exit <- repository.upsert(ClientCertificateIssuance(ClientId("ghost"), "01", t0, t0)).exit
      yield assertTrue(exit.isFailure)
    },
  ) @@ TestAspect.sequential
