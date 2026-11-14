package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.*
import versola.central.configuration.clients.certificates.ClientCertificateIssuanceRepositorySpec
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.PostgresSpec
import zio.*

import java.time.Instant

object PostgresClientCertificateIssuanceRepositorySpec extends PostgresSpec, ClientCertificateIssuanceRepositorySpec:

  import ClientCertificateIssuanceRepositorySpec.*

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
    themeOverrides = None,
    applicationType = ApplicationType.web,
  )

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresClientCertificateIssuanceRepository(xa), PostgresOAuthClientRepository(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run())
      _ <- xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run())
      _ <- xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
      _ <- registered(env, clientId)
      _ <- registered(env, other)
    yield ()

  /** The client in the repository of clients, on tables just emptied: one that is there already is a failure of the reset. */
  private def registered(env: Env, id: ClientId): Task[Unit] =
    env.clients.createClient(record(id)).mapError:
      case failure: Throwable => failure
      case _: ClientAlreadyExists => IllegalStateException(s"client $id was there before the case started")
