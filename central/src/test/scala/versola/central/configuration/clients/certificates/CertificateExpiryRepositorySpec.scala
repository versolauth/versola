package versola.central.configuration.clients.certificates

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.*
import versola.central.configuration.clients.est.{ClientEstRepository, NewEstCertificate}
import versola.central.configuration.edges.{EdgeCertificateRepository, EdgeGroupId, EdgeGroupKind, EdgeId}
import versola.central.configuration.partners.*
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.CertificateMetrics.Expiry
import versola.util.{DatabaseSpecBase, RedirectUri}
import zio.*
import zio.test.*

import java.time.Instant

/** What `CertificateExpiryReporter` reads, for any storage: one aggregate for each of the three stores of certificates (the ones central
  * issued to clients, the identities of edges, the ones clients obtained through EST), from rows whose end and length are fixed here.
  * See `PostgresCertificateExpiryRepositorySpec` in `central-postgres-impl` for the one binding.
  *
  * A certificate is in its renewal window when its remaining life is below the window (strictly, as `expiringBefore` is) and past the middle
  * of it when below half of it; an expired one is in both. */
trait CertificateExpiryRepositorySpec extends DatabaseSpecBase[CertificateExpiryRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val tenantId = TenantId("tenant-a")
  private val now = Instant.parse("2026-11-15T12:00:00Z")
  private val day = 86400L
  private val third = 1.0 / 3

  private def at(days: Double): Instant = now.plusSeconds((days * day).toLong)

  private def client(id: String) =
    OAuthClientRecord(
      id = ClientId(id),
      tenantId = tenantId,
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
      partnerId = None,
      approval = ClientApproval.Approved,
    )

  private def partner(id: String, group: EdgeGroupId, status: PartnerStatus) =
    PartnerRecord(
      id = PartnerId(id),
      tenantId = tenantId,
      displayName = s"Partner $id",
      logoUri = None,
      contact = PartnerContact(email = s"ops@$id.example", phone = None),
      status = status,
      limits = PartnerLimits(
        scopes = Set(ScopeToken("openid")),
        redirectDomains = Set("acme.example"),
        resourceDomains = None,
        maxClients = 5,
        maxEdges = 2,
        accessTokenTtlMax = 600,
        refreshTokenTtlMax = 86400,
        clientAuthMethods = Set(AuthMethod.tls_client_auth),
      ),
      groupId = group,
      consoleClientId = ClientId(s"$id-console"),
      settings = PartnerSettings.empty,
      reviewAt = None,
      createdAt = now,
      version = 0L,
    )

  override def testCases(env: CertificateExpiryRepositorySpec.Env) =
    List(
      suite("certificates central issued to clients")(
        test("an empty table is no certificates and no end") {
          for expiry <- env.issuances.expiry(now, 4.days)
          yield assertTrue(expiry == Expiry.Empty)
        },
        test("counts the certificates in the renewal window and past its middle, by the window given, with the earliest end") {
          val rows = Vector(
            ("expired", at(-1)),        // in both: expired and not renewed is the worst of them
            ("a-day-left", at(1)),      // in both: less than half of 4 days left
            ("two-days-left", at(2)),   // in the window; the middle is not past: exactly half is left
            ("three-days-left", at(3)), // in the window
            ("window-edge", at(4)),     // not in: strictly inside, as `expiringBefore` is
            ("ten-days-left", at(10)),
          )
          for
            _ <- env.fixtures.tenant
            _ <- ZIO.foreachDiscard(rows)((id, notAfter) =>
              env.clients.createClient(client(id)) *> env.issuances.upsert(ClientCertificateIssuance(
                ClientId(id),
                "01",
                notAfter,
                now.minusSeconds(day),
              )),
            )
            expiry <- env.issuances.expiry(now, 4.days)
            wider <- env.issuances.expiry(now, 20.days)
            due <- env.issuances.expiringBefore(now.plus(4.days))
          yield assertTrue(
            expiry == Expiry(tracked = 6, earliestNotAfter = Some(at(-1)), inRenewalWindow = 4, pastRenewalMidpoint = 2),
            // the window is the argument and not a constant of the store
            wider == Expiry(tracked = 6, earliestNotAfter = Some(at(-1)), inRenewalWindow = 6, pastRenewalMidpoint = 5),
            // and it is the set a renewal pass renews
            due.size == expiry.inRenewalWindow,
          )
        },
        test("a client that is deleted leaves nothing to count") {
          for
            _ <- env.fixtures.tenant
            _ <- env.clients.createClient(client("gone"))
            _ <- env.issuances.upsert(ClientCertificateIssuance(ClientId("gone"), "01", at(1), now))
            _ <- env.clients.deleteClient(ClientId("gone"))
            expiry <- env.issuances.expiry(now, 4.days)
          yield assertTrue(expiry == Expiry.Empty)
        },
      ),
      suite("identities of edges")(
        test("an empty table is no certificates and no end") {
          for expiry <- env.edgeCertificates.expiry(now, third)
          yield assertTrue(expiry == Expiry.Empty)
        },
        test("the window of each is a third of its own life, and only the certificate issued last of an edge counts") {
          def issue(edge: String, serial: String, lifetimeDays: Int, remainingDays: Double) =
            env.edgeCertificates.record(EdgeId(edge), serial, at(remainingDays), at(remainingDays).minusSeconds(lifetimeDays * day))
          for
            _ <-
              ZIO.foreachDiscard(List("expired", "late", "nearly", "fresh", "renewed", "short"))(id => env.fixtures.edge(id, EdgeGroupId("default")))
            _ <- issue("expired", "01", 30, -1) // in both
            _ <- issue("late", "02", 30, 4)     // 4 < 10 and 4 < 5: in both
            _ <- issue("nearly", "03", 30, 8)   // in the window, not past its middle
            _ <- issue("fresh", "04", 30, 20)
            // an edge that renewed: the certificate it was given before it is of no interest, the one given last is
            _ <- issue("renewed", "05", 30, -3)
            _ <- issue("renewed", "06", 30, 25)
            // a certificate of 14 days (a partner's): six days left is more than a third of it, though less than a third of 30 days
            _ <- issue("short", "07", 14, 6)
            expiry <- env.edgeCertificates.expiry(now, third)
          yield assertTrue(expiry == Expiry(tracked = 6, earliestNotAfter = Some(at(-1)), inRenewalWindow = 3, pastRenewalMidpoint = 2))
        },
        test("the edges of a suspended partner are left out, those of an active or an onboarding one are not") {
          val suspended = EdgeGroupId("partner-suspended")
          val active = EdgeGroupId("partner-active")
          val onboarding = EdgeGroupId("partner-onboarding")
          def issue(edge: String) = env.edgeCertificates.record(EdgeId(edge), "01", at(-1), at(-31))
          for
            _ <- env.fixtures.tenant
            _ <- ZIO.foreachDiscard(List(suspended, active, onboarding))(env.fixtures.group(_, EdgeGroupKind.Partner))
            _ <- env.partners.create(partner("suspended", suspended, PartnerStatus.Onboarding))
            _ <- env.partners.create(partner("active", active, PartnerStatus.Active))
            _ <- env.partners.create(partner("onboarding", onboarding, PartnerStatus.Onboarding))
            _ <- env.partners.setStatus(PartnerId("suspended"), PartnerStatus.Suspended)
            _ <- ZIO.foreachDiscard(List("edge-suspended" -> suspended, "edge-active" -> active, "edge-onboarding" -> onboarding)): (edge, group) =>
              env.fixtures.edge(edge, group) *> issue(edge)
            expiry <- env.edgeCertificates.expiry(now, third)
            // resumed, the edge is counted again
            _ <- env.partners.setStatus(PartnerId("suspended"), PartnerStatus.Active)
            resumed <- env.edgeCertificates.expiry(now, third)
          yield assertTrue(expiry.tracked == 2, expiry.inRenewalWindow == 2, resumed.tracked == 3)
        },
      ),
      suite("certificates clients obtained through EST")(
        test("an empty table is no certificates and no end") {
          for expiry <- env.est.expiry(now, third)
          yield assertTrue(expiry == Expiry.Empty)
        },
        test("the window of each is the share of its own life, and the certificate of the current generation that ends last is the one") {
          def enrol(id: String, lifetimeDays: Int, remainingDays: Double) =
            env.clients.createClient(client(id)) *> env.est.enable(ClientId(id), now) *>
              env.est.recordEnrollment(
                NewEstCertificate(ClientId(id), s"thumb-$id", "01", at(remainingDays), at(remainingDays).minusSeconds(lifetimeDays * day)),
                now,
              )
          for
            _ <- env.fixtures.tenant
            _ <- enrol("expired", 14, -1) // in both
            _ <- enrol("late", 14, 2)     // 2 < 4.67 and 2 < 2.33: in both
            _ <- enrol("nearly", 14, 4)   // in the window, not past its middle
            _ <- enrol("fresh", 14, 12)
            // renewed: the certificate before is superseded and is no longer the one that counts
            _ <- enrol("renewed", 14, 1)
            _ <- env.est.recordRenewal(
              presentedThumbprint = "thumb-renewed",
              certificate = NewEstCertificate(ClientId("renewed"), "thumb-renewed-2", "02", at(13), at(-1)),
              generation = 1,
              now = now,
              maxAge = 365.days,
              autoExtend = true,
            )
            // the right to renew was revoked: a certificate of an earlier generation is not what the client renews with
            _ <- enrol("revoked", 14, -1)
            _ <- env.est.revoke(ClientId("revoked"))
            expiry <- env.est.expiry(now, third)
            narrower <- env.est.expiry(now, 0.1)
          yield assertTrue(
            expiry == Expiry(tracked = 5, earliestNotAfter = Some(at(-1)), inRenewalWindow = 3, pastRenewalMidpoint = 2),
            // the share is the argument: a tenth of 14 days is 1.4, which only the expired one is inside
            narrower == Expiry(tracked = 5, earliestNotAfter = Some(at(-1)), inRenewalWindow = 1, pastRenewalMidpoint = 1),
          )
        },
      ),
    )

object CertificateExpiryRepositorySpec:
  /** The rows the repositories under test do not write: what a certificate belongs to. */
  trait Fixtures:
    def tenant: Task[Unit]
    def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit]
    def edge(id: String, group: EdgeGroupId): Task[Unit]

  case class Env(
      issuances: ClientCertificateIssuanceRepository,
      edgeCertificates: EdgeCertificateRepository,
      est: ClientEstRepository,
      clients: OAuthClientRepository,
      partners: PartnerRepository,
      fixtures: Fixtures,
  )
