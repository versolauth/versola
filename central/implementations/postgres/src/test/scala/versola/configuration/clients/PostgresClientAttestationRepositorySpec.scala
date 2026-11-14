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

/** The table behind a client's attestation policy: that what is stored is what is read back, builds and all, that a client
  * has one policy at a time, and that deleting a client leaves none behind. */
object PostgresClientAttestationRepositorySpec extends PostgresSpec:

  private val mobile = ClientId("mobile-a")
  private val other = ClientId("mobile-b")
  private val t0 = Instant.parse("2026-03-01T00:00:00Z")

  private val ios = IosAttestation(
    appIds = Set("ABCDE12345.com.example.app", "ABCDE12345.com.example.beta"),
    environment = IosEnvironment.development,
  )
  private val android = AndroidApp(packageName = "com.example.app", certificateDigests = Set("ab" * 32, "cd" * 32))
  private val enforcing = ClientAttestation(mode = AttestationMode.enforce, ios = Some(ios), android = List(android))
  private val reporting = ClientAttestation(mode = AttestationMode.report, ios = None, android = List(android))

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

  private val setup =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run())
      _ <- xa.connect(sql"INSERT INTO themes (id, css) VALUES ('default', '') ON CONFLICT (id) DO NOTHING".update.run())
      _ <- xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
      clients = PostgresOAuthClientRepository(xa)
      _ <- clients.createClient(record(mobile))
      _ <- clients.createClient(record(other))
    yield (clients, PostgresClientAttestationRepository(xa))

  def spec = suite("PostgresClientAttestationRepository")(
    test("a client with no policy has none") {
      for
        (_, repository) <- setup
        found <- repository.find(mobile)
        all <- repository.all
      yield assertTrue(found.isEmpty, all.isEmpty)
    },
    test("what is stored is what is read back, builds and environment included") {
      for
        (_, repository) <- setup
        _ <- repository.put(mobile, enforcing, t0)
        found <- repository.find(mobile)
      yield assertTrue(found == Some(enforcing))
    },
    test("a second put replaces the first rather than adding to it") {
      for
        (_, repository) <- setup
        _ <- repository.put(mobile, enforcing, t0)
        _ <- repository.put(mobile, reporting, t0.plusSeconds(60))
        found <- repository.find(mobile)
      yield assertTrue(found == Some(reporting), found.flatMap(_.ios).isEmpty)
    },
    test("all lists every client's policy, one each") {
      for
        (_, repository) <- setup
        _ <- repository.put(mobile, enforcing, t0)
        _ <- repository.put(other, reporting, t0)
        all <- repository.all
      yield assertTrue(all == Map(mobile -> enforcing, other -> reporting))
    },
    test("delete says whether there was a policy to remove") {
      for
        (_, repository) <- setup
        _ <- repository.put(mobile, enforcing, t0)
        first <- repository.delete(mobile)
        second <- repository.delete(mobile)
        found <- repository.find(mobile)
      yield assertTrue(first, !second, found.isEmpty)
    },
    test("a policy for a client that does not exist is not stored") {
      for
        (_, repository) <- setup
        result <- repository.put(ClientId("nobody"), enforcing, t0).either
      yield assertTrue(result.isLeft)
    },
    test("deleting a client takes its policy with it, and leaves the other client's") {
      for
        (clients, repository) <- setup
        _ <- repository.put(mobile, enforcing, t0)
        _ <- repository.put(other, reporting, t0)
        _ <- clients.deleteClient(mobile)
        gone <- repository.find(mobile)
        kept <- repository.find(other)
      yield assertTrue(gone.isEmpty, kept == Some(reporting))
    },
  ) @@ TestAspect.sequential
