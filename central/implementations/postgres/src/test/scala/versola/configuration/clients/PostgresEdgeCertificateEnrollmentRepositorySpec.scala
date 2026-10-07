package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.*
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

object PostgresEdgeCertificateEnrollmentRepositorySpec extends PostgresSpec:

  private val clientId = ClientId("mobile-app")
  private val other = ClientId("other-app")
  private val t0 = Instant.parse("2026-03-01T00:00:00Z")

  private def record(id: ClientId) = OAuthClientRecord(
    id = id, tenantId = TenantId("tenant-a"), clientName = Map("en" -> "App"),
    redirectUris = Set(RedirectUri("https://example.com/callback")), scope = Set.empty, secret = None, previousSecret = None,
    accessTokenTtl = 5.minutes, refreshTokenTtl = 7776000.seconds, permissions = Set.empty, theme = "default",
    authFlow = None, registrationFlow = None, otpTemplateId = "default", frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false, backChannelLogoutUri = None, logoUri = None, policyUri = None, tosUri = None,
    consentFlow = None, dpopBoundAccessTokens = false, dpopSigningAlgs = Set.empty, dpopMinRsaKeySize = None,
    authMethod = AuthMethod.none, mtlsAuth = None, certificateBoundAccessTokens = false, jwks = None,
    requireSignedRequestObject = false, requirePushedAuthorizationRequests = false, edgeSigningKey = None,
    edgeClientCertificate = None, template = None, createdAt = Instant.EPOCH,
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
    yield (xa, clients, PostgresEdgeCertificateEnrollmentRepository(xa))

  def spec = suite("PostgresEdgeCertificateEnrollmentRepository")(
    test("enrolls a client once, however often it is asked") {
      for
        (_, _, repository) <- setup
        before <- repository.isEnrolled(clientId)
        _ <- repository.enroll(clientId, t0)
        _ <- repository.enroll(clientId, t0.plusSeconds(60))
        after <- repository.isEnrolled(clientId)
        all <- repository.enrolledClients
      yield assertTrue(!before, after, all == Set(clientId))
    },
    test("records what was last signed, and for which edge") {
      for
        (xa, _, repository) <- setup
        _ <- repository.enroll(clientId, t0)
        _ <- repository.recordIssued(clientId, "ab12", t0.plusSeconds(5), EdgeId("edge-1"))
        row <- xa.connect(sql"SELECT last_serial, last_edge_id FROM edge_certificate_enrollment WHERE client_id = 'mobile-app'".query[(String, String)].run())
      yield assertTrue(row == Vector(("ab12", "edge-1")))
    },
    test("deleting the client removes its enrolment") {
      for
        (_, clients, repository) <- setup
        _ <- repository.enroll(clientId, t0)
        _ <- clients.deleteClient(clientId)
        enrolled <- repository.isEnrolled(clientId)
      yield assertTrue(!enrolled)
    },
    test("a client that does not exist cannot be enrolled") {
      for
        (_, _, repository) <- setup
        exit <- repository.enroll(ClientId("ghost"), t0).exit
      yield assertTrue(exit.isFailure)
    },
  ) @@ TestAspect.sequential
