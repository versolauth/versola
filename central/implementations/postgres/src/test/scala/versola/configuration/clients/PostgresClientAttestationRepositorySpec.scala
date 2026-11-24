package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.*
import versola.central.configuration.clients.attestation.*
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** The wiring of [[ClientAttestationRepositorySpec]] to Postgres, and what only the table promises: that deleting a client leaves no
  * policy of it behind. */
object PostgresClientAttestationRepositorySpec extends PostgresSpec, ClientAttestationRepositorySpec:

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
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = None,
    createdAt = Instant.EPOCH,
    themeOverrides = None,
    applicationType = ApplicationType.native,
    partnerId = None,
    approval = ClientApproval.Approved,
  )

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ClientAttestationRepositorySpec.Env(PostgresClientAttestationRepository(xa))

  override def beforeEach(env: ClientAttestationRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run())
      _ <- xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run())
      _ <- xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
      clients = PostgresOAuthClientRepository(xa)
      _ <- clients.createClient(record(mobile)).mapError(error => RuntimeException(error.toString))
      _ <- clients.createClient(record(other)).mapError(error => RuntimeException(error.toString))
    yield ()

  override def implementationCases(env: ClientAttestationRepositorySpec.Env) =
    List(
      test("deleting a client takes its policy with it, and leaves the other client's") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- env.repository.put(mobile, enforcing, t0)
          _ <- env.repository.put(other, reporting, t0)
          _ <- PostgresOAuthClientRepository(xa).deleteClient(mobile)
          gone <- env.repository.find(mobile)
          kept <- env.repository.find(other)
        yield assertTrue(gone.isEmpty, kept == Some(reporting))
      },
    )
