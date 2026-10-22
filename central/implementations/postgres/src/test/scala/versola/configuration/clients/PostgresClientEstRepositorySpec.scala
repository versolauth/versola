package versola.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.clients.*
import versola.central.configuration.clients.est.{NewEstCertificate, RenewalRecorded}
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** The tables behind EST enrolment: that a one-time token really is spent once, that a certificate is found by its
  * thumbprint, and that deleting a client leaves nothing behind. */
object PostgresClientEstRepositorySpec extends PostgresSpec:

  private val clientId = ClientId("bank-a")
  private val other = ClientId("bank-b")
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
    yield (xa, clients, PostgresClientEstRepository(xa))

  def spec = suite("PostgresClientEstRepository")(
    test("enables a client once, however often it is asked") {
      for
        (_, _, repository) <- setup
        before <- repository.isEnabled(clientId)
        _ <- repository.enable(clientId, t0)
        _ <- repository.enable(clientId, t0.plusSeconds(60))
        after <- repository.isEnabled(clientId)
        all <- repository.all
      yield assertTrue(!before, after, all.map(_.clientId) == Vector(clientId), all.head.enrolledAt == t0)
    },
    test("a token is spent once, and only while it is good") {
      for
        (_, _, repository) <- setup
        _ <- repository.enable(clientId, t0)
        _ <- repository.createToken("hash-1", clientId, t0.plusSeconds(3600))
        peeked <- repository.peekToken("hash-1", t0)
        first <- repository.consumeToken("hash-1", t0)
        second <- repository.consumeToken("hash-1", t0)
        peekedAfter <- repository.peekToken("hash-1", t0)
        _ <- repository.createToken("hash-2", clientId, t0.plusSeconds(3600))
        expired <- repository.consumeToken("hash-2", t0.plusSeconds(3600))
        unknown <- repository.consumeToken("hash-3", t0)
      yield assertTrue(
        peeked == Some(clientId),
        first == Some(clientId),
        second.isEmpty,
        peekedAfter.isEmpty,
        expired.isEmpty,
        unknown.isEmpty,
      )
    },
    test("an enrolment starts a generation and the chain; a renewal replaces the certificate in it, and shows in the listing") {
      for
        (_, _, repository) <- setup
        _ <- repository.enable(clientId, t0)
        pending <- repository.all
        generation0 <- repository.generation(clientId)
        _ <- repository.recordEnrollment(NewEstCertificate(clientId, "aa", "1", t0.plusSeconds(100), t0), t0)
        generation1 <- repository.generation(clientId)
        outcome <-
          repository.recordRenewal("aa", NewEstCertificate(clientId, "bb", "2", t0.plusSeconds(200), t0), 1, t0.plusSeconds(10), 1.hour, false)
        first <- repository.findCertificate("aa")
        second <- repository.findCertificate("bb")
        missing <- repository.findCertificate("cc")
        chain <- repository.chainStartedAt(clientId)
        listed <- repository.all
      yield assertTrue(
        pending.head.latestNotAfter.isEmpty,
        pending.head.chainStartedAt.isEmpty,
        generation0 == Some(0),
        generation1 == Some(1),
        outcome == RenewalRecorded.Recorded(t0),
        first.exists(c => c.generation == 1 && c.supersededAt.contains(t0.plusSeconds(10))),
        second.exists(c => c.generation == 1 && c.supersededAt.isEmpty && c.serial == "2"),
        missing.isEmpty,
        chain == Some(t0),
        listed.head.latestNotAfter == Some(t0.plusSeconds(200)),
      )
    },
    test("a renewal is not recorded for a generation that is over, or a chain that has ended unless it is extended by itself") {
      for
        (_, _, repository) <- setup
        _ <- repository.enable(clientId, t0)
        _ <- repository.recordEnrollment(NewEstCertificate(clientId, "aa", "1", t0.plusSeconds(100), t0), t0)
        ended <-
          repository.recordRenewal("aa", NewEstCertificate(clientId, "bb", "2", t0.plusSeconds(200), t0), 1, t0.plusSeconds(7200), 1.hour, false)
        extended <-
          repository.recordRenewal("aa", NewEstCertificate(clientId, "cc", "3", t0.plusSeconds(300), t0), 1, t0.plusSeconds(7200), 1.hour, true)
        chain <- repository.chainStartedAt(clientId)
        _ <- repository.revoke(clientId)
        revoked <-
          repository.recordRenewal("cc", NewEstCertificate(clientId, "dd", "4", t0.plusSeconds(400), t0), 1, t0.plusSeconds(7201), 1.hour, true)
        generation <- repository.generation(clientId)
        chainAfter <- repository.chainStartedAt(clientId)
        listed <- repository.all
      yield assertTrue(
        ended == RenewalRecorded.ChainEnded,
        extended == RenewalRecorded.Recorded(t0.plusSeconds(7200)),
        chain == Some(t0.plusSeconds(7200)),
        revoked == RenewalRecorded.GenerationChanged,
        generation == Some(2),
        chainAfter.isEmpty,
        listed.head.latestNotAfter.isEmpty,
      )
    },
    test("a new token replaces the unused ones of the client, and a revocation withdraws them") {
      for
        (_, _, repository) <- setup
        _ <- repository.enable(clientId, t0)
        _ <- repository.enable(other, t0)
        _ <- repository.createToken("h1", clientId, t0.plusSeconds(3600))
        _ <- repository.createToken("o1", other, t0.plusSeconds(3600))
        _ <- repository.createToken("h2", clientId, t0.plusSeconds(3600))
        first <- repository.peekToken("h1", t0)
        second <- repository.peekToken("h2", t0)
        otherClient <- repository.peekToken("o1", t0)
        _ <- repository.revoke(clientId)
        revoked <- repository.peekToken("h2", t0)
        otherAfter <- repository.peekToken("o1", t0)
        _ <- repository.createToken("h3", clientId, t0.plusSeconds(3600))
        _ <- repository.consumeToken("h3", t0)
        _ <- repository.releaseToken("h3")
        released <- repository.peekToken("h3", t0)
      yield assertTrue(
        first.isEmpty,
        second == Some(clientId),
        otherClient == Some(other),
        revoked.isEmpty,
        otherAfter == Some(other),
        released == Some(clientId),
      )
    },
    test("deleting a client takes its EST records with it") {
      for
        (_, clients, repository) <- setup
        _ <- repository.enable(other, t0)
        _ <- repository.createToken("hash-1", other, t0.plusSeconds(3600))
        _ <- repository.recordEnrollment(NewEstCertificate(other, "aa", "1", t0.plusSeconds(100), t0), t0)
        _ <- clients.deleteClient(other)
        enabled <- repository.isEnabled(other)
        token <- repository.peekToken("hash-1", t0)
        certificate <- repository.findCertificate("aa")
      yield assertTrue(!enabled, token.isEmpty, certificate.isEmpty)
    },
  ) @@ TestAspect.sequential
