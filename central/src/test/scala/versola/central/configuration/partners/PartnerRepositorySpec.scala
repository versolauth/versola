package versola.central.configuration.partners

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.{AuthMethod, ClientId}
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import versola.util.http.RateLimitPolicies
import zio.*
import zio.test.*

import java.time.Instant

/** The partners table: a partner is written once, read by id and by its edge group, and changed by the operator (profile, status,
  * limits) and by itself (settings) in separate statements. */
trait PartnerRepositorySpec extends DatabaseSpecBase[PartnerRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val groupA = EdgeGroupId("partner-acme")
  val groupB = EdgeGroupId("partner-globex")
  val firstPartyGroup = EdgeGroupId("own-edges")
  val now = Instant.parse("2026-11-10T10:00:00Z")

  val limits = PartnerLimits(
    scopes = Set(ScopeToken("openid"), ScopeToken("phone")),
    redirectDomains = Set("acme.example"),
    maxClients = 5,
    maxEdges = 2,
    accessTokenTtlMax = 600,
    refreshTokenTtlMax = 86400,
    clientAuthMethods = Set(AuthMethod.tls_client_auth),
    resourceDomains = None,
  )

  def partner(id: String, groupId: EdgeGroupId): PartnerRecord =
    PartnerRecord(
      id = PartnerId(id),
      tenantId = tenantId,
      displayName = s"Partner $id",
      logoUri = None,
      contact = PartnerContact(email = s"ops@$id.example", phone = None),
      status = PartnerStatus.Onboarding,
      limits = limits,
      groupId = groupId,
      consoleClientId = ClientId(s"$id-console"),
      settings = PartnerSettings.empty,
      reviewAt = None,
      createdAt = now,
      version = 0L,
    )

  override def testCases(env: PartnerRepositorySpec.Env) =
    List(
      test("a partner is created once, and found by id and by its group") {
        val acme = partner("acme", groupA)
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.fixtures.group(groupB, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          _ <- env.repository.create(partner("globex", groupB))
          again <- env.repository.create(acme.copy(displayName = "Other")).flip
          byId <- env.repository.find(acme.id)
          byGroup <- env.repository.findByGroup(groupA)
          none <- env.repository.findByGroup(EdgeGroupId("nobody"))
          all <- env.repository.getAll
        yield assertTrue(
          again == PartnerAlreadyExists(acme.id),
          byId == Some(acme),
          byGroup == Some(acme),
          none.isEmpty,
          all.map(_.id) == Vector(PartnerId("acme"), PartnerId("globex")),
        )
      },
      // the group's kind is not the store's to check: PartnerService creates the group of a partner itself, of kind `partner`
      test("a partner cannot point at a group that does not exist, and two partners cannot share one group") {
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          missing <- env.repository.create(partner("acme", EdgeGroupId("nobody"))).flip
          _ <- env.repository.create(partner("acme", groupA))
          shared <- env.repository.create(partner("globex", groupA)).flip
          all <- env.repository.getAll
        yield assertTrue(
          missing.getMessage.nonEmpty,
          shared == PartnerAlreadyExists(PartnerId("globex")),
          all.map(_.id) == Vector(PartnerId("acme")),
        )
      },
      test("the operator's changes and the partner's own are written apart, and each answers whether the partner exists") {
        val acme = partner("acme", groupA)
        val contact = PartnerContact(email = "security@acme.example", phone = Some("+77010000000"))
        val settings = PartnerSettings(PartnerDefaults(
          backChannelLogoutUri = Some("https://acme.example/logout"),
          accessTokenTtl = Some(300),
          refreshTokenTtl = None,
        ))
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          profile <-
            env.repository.updateProfile(acme.id, 0L, "ACME Ltd", Some("https://acme.example/logo.png"), contact, Some(now.plusSeconds(86400)))
          status <- env.repository.setStatus(acme.id, PartnerStatus.Suspended)
          changed <- env.repository.setLimits(acme.id, limits.copy(maxClients = 9))
          own <- env.repository.setSettings(acme.id, 3L, contact.copy(phone = None), settings)
          nobody <- env.repository.setStatus(PartnerId("nobody"), PartnerStatus.Active)
          found <- env.repository.find(acme.id)
        yield assertTrue(
          profile,
          status,
          changed,
          own,
          !nobody,
          found == Some(acme.copy(
            displayName = "ACME Ltd",
            logoUri = Some("https://acme.example/logo.png"),
            contact = contact.copy(phone = None),
            status = PartnerStatus.Suspended,
            limits = limits.copy(maxClients = 9),
            settings = settings,
            reviewAt = Some(now.plusSeconds(86400)),
            // every write of the row bumped it: the profile, the status, the limits and the settings
            version = 4L,
          )),
        )
      },
      test("a write that names an old version loses and changes nothing; the name and logo alone are written without a version") {
        val acme = partner("acme", groupA)
        val contact = PartnerContact(email = "security@acme.example", phone = None)
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          first <- env.repository.updateProfile(acme.id, 0L, "ACME Ltd", None, contact, None)
          // the same version again, as a second writer that read before the first wrote
          stale <- env.repository.updateProfile(acme.id, 0L, "ACME Inc", None, contact.copy(email = "other@acme.example"), None)
          staleSettings <- env.repository.setSettings(acme.id, 0L, contact.copy(email = "other@acme.example"), PartnerSettings.empty)
          named <- env.repository.setNameAndLogo(acme.id, "ACME Group", Some("https://acme.example/new.png"))
          found <- env.repository.find(acme.id)
        yield assertTrue(
          first,
          !stale,
          !staleSettings,
          named,
          // the contact is the first writer's, and the name and logo came without touching it
          found.exists(p => p.displayName == "ACME Group" && p.logoUri.contains("https://acme.example/new.png") && p.contact == contact),
          found.exists(_.version == 2L),
        )
      },
      test("activation is from onboarding and from nothing else, as the row says now") {
        val acme = partner("acme", groupA)
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          first <- env.repository.activate(acme.id)
          _ <- env.repository.setStatus(acme.id, PartnerStatus.Suspended)
          // a stale read of `onboarding` that gets here after a suspension
          late <- env.repository.activate(acme.id)
          found <- env.repository.find(acme.id)
          nobody <- env.repository.activate(PartnerId("nobody"))
        yield assertTrue(first, !late, !nobody, found.exists(_.status == PartnerStatus.Suspended))
      },
      test(
        "the rate limits are stored once on the partner with a revision of their own: a change raises it, the same value and a failed write do not, the version is left alone",
      ) {
        val acme = partner("acme", groupA)
        val policies = RateLimitPolicies.recommended
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          none <- env.repository.find(acme.id)
          first <- env.repository.setRateLimits(acme.id, Some(policies), Some(0))
          same <- env.repository.setRateLimits(acme.id, Some(policies), Some(1))
          stale <- env.repository.setRateLimits(acme.id, None, Some(0)).flip
          unchecked <- env.repository.setRateLimits(acme.id, None, None)
          read <- env.repository.find(acme.id)
          nobody <- env.repository.setRateLimits(PartnerId("nobody"), Some(policies), None)
        yield assertTrue(
          none.exists(p => p.rateLimits.isEmpty && p.rateLimitsRevision == 0),
          first.exists(p => p.rateLimits.contains(policies) && p.rateLimitsRevision == 1),
          same.exists(p => p.rateLimits.contains(policies) && p.rateLimitsRevision == 1),
          stale == PartnerRateLimitsChanged(acme.id, 1),
          unchecked.exists(p => p.rateLimits.isEmpty && p.rateLimitsRevision == 2),
          read.exists(p => p.rateLimits.isEmpty && p.rateLimitsRevision == 2 && p.version == acme.version),
          nobody.isEmpty,
        )
      },
      test("two writes of the limits at the same revision: one wins and the other gets the conflict, and what is stored is the winner's") {
        val acme = partner("acme", groupA)
        val one = RateLimitPolicies.recommended
        val other = RateLimitPolicies(one.policies.take(1), 2)
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          results <- ZIO.foreachPar(List(one, other, one, other, one, other))(set => env.repository.setRateLimits(acme.id, Some(set), Some(0)).either)
          read <- env.repository.find(acme.id)
          winner = results.collect { case Right(Some(stored)) => stored.rateLimits }
        yield assertTrue(
          winner.size == 1,
          results.count(_.isLeft) == 5,
          results.forall(_.left.forall(_.isInstanceOf[PartnerRateLimitsChanged])),
          read.exists(p => p.rateLimits == winner.head && p.rateLimitsRevision == 1),
        )
      },
      test("the limits are the partner's, not an edge's: an edge added later changes nothing of them, and deleting the last edge keeps them") {
        val acme = partner("acme", groupA)
        val policies = RateLimitPolicies.recommended
        for
          _ <- env.fixtures.group(groupA, EdgeGroupKind.Partner)
          _ <- env.repository.create(acme)
          _ <- env.fixtures.edge("edge-acme-1", groupA)
          _ <- env.repository.setRateLimits(acme.id, Some(policies), None)
          _ <- env.fixtures.edge("edge-acme-2", groupA)
          withTwo <- env.repository.findByGroup(groupA)
          _ <- env.fixtures.deleteEdge("edge-acme-1")
          _ <- env.fixtures.deleteEdge("edge-acme-2")
          withNone <- env.repository.findByGroup(groupA)
          again <- env.repository.setRateLimits(acme.id, Some(policies.copy(replicas = 3)), Some(1))
        yield assertTrue(
          withTwo.exists(p => p.rateLimits.contains(policies) && p.rateLimitsRevision == 1),
          withNone.exists(p => p.rateLimits.contains(policies) && p.rateLimitsRevision == 1),
          // a partner with no edge can set them
          again.exists(p => p.rateLimits.exists(_.replicas == 3) && p.rateLimitsRevision == 2),
        )
      },
    )

object PartnerRepositorySpec:
  /** The rows the repository under test does not write: the edge groups partners point at. */
  trait Fixtures:
    def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit]
    def edge(id: String, group: EdgeGroupId): Task[Unit]
    def deleteEdge(id: String): Task[Unit]

  case class Env(repository: PartnerRepository, fixtures: Fixtures)
