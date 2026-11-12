package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.audit.{CentralAudit, RecordedAudit}
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer}
import versola.central.configuration.clients.{AuthMethod, ClientId}
import versola.central.configuration.partners.{
  PartnerContact,
  PartnerId,
  PartnerLimits,
  PartnerRecord,
  PartnerRegistry,
  PartnerSettings,
  PartnerStatus,
}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.{CertificateSubject, SecureRandom, TestCertificates}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object EdgeIdentityServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-a")
  private val otherEdgeId = EdgeId("edge-b")
  private val partnerGroup = EdgeGroupId("partner-acme")

  private def edgeRecord(id: EdgeId, groupId: EdgeGroupId = EdgeGroup.DefaultId, kind: EdgeGroupKind = EdgeGroupKind.FirstParty) =
    EdgeRecord(
      id = id,
      publicKey = Json.Obj(),
      oldPublicKey = None,
      requireDpopNonce = true,
      groupId = groupId,
      groupKind = kind,
      overloadPolicy = None,
      accessLogPolicy = None,
      rateLimitPolicies = None,
      policyMeta = None,
      publicUrl = None,
    )

  private class InMemoryTokens(state: Ref[Map[String, (EdgeId, Instant, Boolean)]]) extends EdgeEnrollmentTokenRepository:
    override def create(tokenHash: String, edgeId: EdgeId, expiresAt: Instant): Task[Unit] =
      state.update(_.updated(tokenHash, (edgeId, expiresAt, false)))

    override def peek(tokenHash: String, now: Instant): Task[Option[EdgeId]] =
      state.get.map(_.get(tokenHash).collect { case (edge, expiresAt, false) if expiresAt.isAfter(now) => edge })

    override def consume(tokenHash: String, now: Instant): Task[Option[EdgeId]] =
      state.modify: tokens =>
        tokens.get(tokenHash) match
          case Some((edge, expiresAt, false)) if expiresAt.isAfter(now) =>
            (Some(edge), tokens.updated(tokenHash, (edge, expiresAt, true)))
          case _ => (None, tokens)

    override def revoke(edgeId: EdgeId): Task[Int] =
      state.modify(tokens => (tokens.count((_, v) => v._1 == edgeId && !v._3), tokens.filterNot((_, v) => v._1 == edgeId && !v._3)))

    def hashes: UIO[Set[String]] = state.get.map(_.keySet)

  private class InMemoryCertificates(state: Ref[Vector[(EdgeId, String)]]) extends EdgeCertificateRepository:
    override def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit] =
      state.update(_ :+ (edgeId -> serial))

    override def latest: Task[Map[EdgeId, EdgeCertificateRepository.Issued]] = ZIO.succeed(Map.empty)

    def all: UIO[Vector[(EdgeId, String)]] = state.get

  /** Issues what was asked, the way a CA that signs the request as it is does. */
  private val honestIssuer: ClientCertificateIssuer =
    (request: CertificateSigningRequest) =>
      ZIO.succeed(TestCertificates.generate(subject = s"CN=${request.commonName}", uris = request.uris).certificatePem)

  /** Issues what was asked and remembers the lifetime each request asked for. */
  private class RecordingIssuer extends ClientCertificateIssuer:
    val validities = Unsafe.unsafe(Ref.unsafe.make(Vector.empty[Duration])(using _))

    override def sign(request: CertificateSigningRequest): Task[String] =
      validities.update(_ :+ request.validity) *> honestIssuer.sign(request)

  /** Issues a certificate that names a different edge than the one asked for. */
  private val lyingIssuer: ClientCertificateIssuer =
    (_: CertificateSigningRequest) =>
      ZIO.succeed(TestCertificates.generate(uris = List(EdgeCertificate.uriFor(otherEdgeId))).certificatePem)

  private class Env(
      edges: List[EdgeRecord],
      issuer: ClientCertificateIssuer = honestIssuer,
      config: CentralConfig = TestCentralConfig.config,
      partnerStatus: PartnerStatus = PartnerStatus.Active,
  ):
    val edgeService = stub[EdgeService]
    val partners = stub[PartnerRegistry]
    val tokens = InMemoryTokens(Unsafe.unsafe(Ref.unsafe.make(Map.empty[String, (EdgeId, Instant, Boolean)])(using _)))
    val certificates = InMemoryCertificates(Unsafe.unsafe(Ref.unsafe.make(Vector.empty[(EdgeId, String)])(using _)))
    val audit = RecordedAudit.unsafe
    val service: URIO[SecureRandom, EdgeIdentityService] =
      for
        _ <- edgeService.find.returnsZIO(id => ZIO.succeed(edges.find(_.id == id)))
        _ <- edgeService.getAllEdges.succeedsWith(edges.toVector)
        _ <- partners.findByGroup.returnsZIO(group => ZIO.succeed(Some(partnerRecord(group, partnerStatus))))
        random <- ZIO.service[SecureRandom]
      yield EdgeIdentityService.Impl(
        edgeService,
        tokens,
        certificates,
        EdgeCertificateIssuer(issuer),
        random,
        config,
        audit,
        partners,
      )

  private def partnerRecord(group: EdgeGroupId, status: PartnerStatus) = PartnerRecord(
    id = PartnerId(group),
    tenantId = TenantId("tenant-a"),
    displayName = "Acme",
    logoUri = None,
    contact = PartnerContact("ops@acme.example", None),
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
      rateLimitPolicy = "partner-default",
    ),
    groupId = group,
    consoleClientId = ClientId(s"$group-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = Instant.EPOCH,
    version = 0L,
  )

  private def partnerEdge(id: EdgeId) = edgeRecord(id, partnerGroup, EdgeGroupKind.Partner)

  private def csrFor(edge: EdgeRecord): UIO[String] =
    CertificateSubject.generate(EdgeIdentityService.subjectFor(edge)).orDie.map(_.csrPem)

  def spec = suite("EdgeIdentityService")(
    test("enrolling an edge records cert.enrolled by that edge, and a refused enrolment records nothing") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        refused <- service.enroll(edgeId, "not-a-token", csr).flip
        none <- env.audit.drafts
        _ <- service.enroll(edgeId, issued.token, csr)
        recorded <- env.audit.drafts
      yield assertTrue(
        refused == EdgeIdentityService.InvalidToken,
        none.isEmpty,
        recorded.map(_.kind) == Vector(CentralAudit.CertEnrolled),
        recorded.head.actor == Some((CentralAudit.Actors.Edge, Some(edgeId.toString))),
        recorded.head.target == Some(("edge", edgeId.toString)),
      )
    },
    test("renewing records cert.renewed by that edge, and a request that names another edge records nothing") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge, edgeRecord(otherEdgeId)))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        _ <- service.enroll(edgeId, issued.token, csr)
        refused <- csrFor(edgeRecord(otherEdgeId)).flatMap(service.renew(edgeId, _)).flip
        _ <- csrFor(edge).flatMap(service.renew(edgeId, _))
        recorded <- env.audit.drafts
      yield assertTrue(
        refused.isInstanceOf[EdgeIdentityService.Refused],
        recorded.map(_.kind) == Vector(CentralAudit.CertEnrolled, CentralAudit.CertRenewed),
        recorded.last.actor == Some((CentralAudit.Actors.Edge, Some(edgeId.toString))),
      )
    },
    test("the certificate of an edge names the edge and its group, and nothing else") {
      val subject = EdgeIdentityService.subjectFor(edgeRecord(edgeId, partnerGroup, EdgeGroupKind.Partner))
      assertTrue(
        subject.uris == List("urn:versola:edge:edge-a", "urn:versola:edge-group:partner-acme"),
        subject.dnsNames.isEmpty,
        subject.emailAddresses.isEmpty,
        subject.ipAddresses.isEmpty,
      )
    },
    test("an enrollment token is created for a registered edge, and only its hash is kept") {
      val env = Env(List(edgeRecord(edgeId)))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        hashes <- env.tokens.hashes
      yield assertTrue(hashes.size == 1, !hashes.contains(issued.token), issued.token.nonEmpty)
    },
    test("a token cannot be created for an edge that is not registered") {
      val env = Env(Nil)
      for
        service <- env.service
        failure <- service.issueEnrollmentToken(edgeId).flip
      yield assertTrue(failure.isInstanceOf[EdgeIdentityService.Refused])
    },
    test("a token is traded for the certificate of its edge, once") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        enrolledIdentity <- service.enroll(edgeId, issued.token, csr)
        chain = enrolledIdentity.chain
        again <- service.enroll(edgeId, issued.token, csr).flip
        recorded <- env.certificates.all
      yield assertTrue(
        chain.contains("BEGIN CERTIFICATE"),
        again == EdgeIdentityService.InvalidToken,
        recorded.map(_._1) == Vector(edgeId),
      )
    },
    test("an unregistered edge and a registered one with no good token get the same answer, whatever the request") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        unregistered <- service.enroll(EdgeId("nobody"), "not-a-token", "garbage").flip
        registered <- service.enroll(edgeId, "not-a-token", "garbage").flip
        right <- csrFor(edge)
        registeredGoodRequest <- service.enroll(edgeId, "not-a-token", right).flip
      yield assertTrue(
        unregistered == EdgeIdentityService.InvalidToken,
        registered == EdgeIdentityService.InvalidToken,
        registeredGoodRequest == EdgeIdentityService.InvalidToken,
      )
    },
    test("a request that is not even a certificate request is not parsed before the token is known good") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        withToken <- service.enroll(edgeId, issued.token, "garbage").flip
        stillGood <- csrFor(edge).flatMap(csr => service.enroll(edgeId, issued.token, csr))
      yield assertTrue(withToken.isInstanceOf[EdgeIdentityService.Refused], stillGood.chain.contains("BEGIN CERTIFICATE"))
    },
    test("a token of another edge does not enrol this one") {
      val edge = edgeRecord(edgeId)
      val other = edgeRecord(otherEdgeId)
      val env = Env(List(edge, other))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(otherEdgeId)
        csr <- csrFor(edge)
        failure <- service.enroll(edgeId, issued.token, csr).flip
      yield assertTrue(failure == EdgeIdentityService.InvalidToken)
    },
    test("an expired token is refused the same way as an unknown one") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        _ <- TestClock.adjust(EdgeIdentityService.TokenValidity + 1.second)
        csr <- csrFor(edge)
        expired <- service.enroll(edgeId, issued.token, csr).flip
        unknown <- service.enroll(edgeId, "not-a-token", csr).flip
      yield assertTrue(expired == EdgeIdentityService.InvalidToken, unknown == EdgeIdentityService.InvalidToken)
    },
    test("a request that names something else is refused before the token is spent") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        // asks for a group the edge is not in
        wrong <- csrFor(edgeRecord(edgeId, partnerGroup))
        refused <- service.enroll(edgeId, issued.token, wrong).flip
        right <- csrFor(edge)
        chain <- service.enroll(edgeId, issued.token, right)
      yield assertTrue(refused.isInstanceOf[EdgeIdentityService.Refused], chain.chain.contains("BEGIN CERTIFICATE"))
    },
    test("a certificate the CA returns with other names than asked is not handed to the edge") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge), lyingIssuer)
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        failure <- service.enroll(edgeId, issued.token, csr).flip
        recorded <- env.certificates.all
      yield assertTrue(failure.isInstanceOf[EdgeIdentityService.Refused], recorded.isEmpty)
    },
    // #549 decision 8: renewal is always on, for both kinds; a partner is stopped by suspending it, not by a chain's age.
    test("an edge of either kind renews its way along, however old its enrollment: there is no chain that ends") {
      val own = edgeRecord(edgeId)
      val partner = partnerEdge(otherEdgeId)
      val env = Env(List(own, partner))
      for
        service <- env.service
        issuedOwn <- service.issueEnrollmentToken(edgeId)
        issuedPartner <- service.issueEnrollmentToken(otherEdgeId)
        _ <- csrFor(own).flatMap(service.enroll(edgeId, issuedOwn.token, _))
        _ <- csrFor(partner).flatMap(service.enroll(otherEdgeId, issuedPartner.token, _))
        _ <- TestClock.adjust(400.days)
        ownLate <- csrFor(own).flatMap(service.renew(edgeId, _))
        partnerLate <- csrFor(partner).flatMap(service.renew(otherEdgeId, _))
        _ <- TestClock.adjust(400.days)
        partnerLater <- csrFor(partner).flatMap(service.renew(otherEdgeId, _))
      yield assertTrue(
        ownLate.chain.contains("BEGIN CERTIFICATE"),
        partnerLate.chain.contains("BEGIN CERTIFICATE"),
        partnerLater.chain.contains("BEGIN CERTIFICATE"),
      )
    },
    test("a partner's edge is asked a certificate of edge-certificates.partner-validity, a first-party edge the global validity") {
      val own = edgeRecord(edgeId)
      val partner = partnerEdge(otherEdgeId)
      val issuer = RecordingIssuer()
      val env = Env(
        List(own, partner),
        issuer,
        TestCentralConfig.config.copy(edgeCertificates = Some(CentralConfig.ClientCertificatesConfig(validity = 30.days, partnerValidity = 7.days))),
      )
      for
        service <- env.service
        _ <- csrFor(own).flatMap(service.renew(edgeId, _))
        _ <- csrFor(partner).flatMap(service.renew(otherEdgeId, _))
        asked <- issuer.validities.get
      yield assertTrue(asked == Vector(CentralConfig.ClientCertificatesConfig().validity, 7.days))
    },
    test("an enrollment token is valid for an hour unless asked for longer, and for no more than a day") {
      val env = Env(List(edgeRecord(edgeId)))
      for
        service <- env.service
        now <- Clock.instant
        byDefault <- service.issueEnrollmentToken(edgeId)
        afterFiveHours <- service.issueEnrollmentToken(edgeId, 5.hours)
        tooLong <- service.issueEnrollmentToken(edgeId, 25.hours).flip
        none <- service.issueEnrollmentToken(edgeId, Duration.Zero).flip
      yield assertTrue(
        byDefault.expiresAt == now.plusSeconds(3600),
        afterFiveHours.expiresAt == now.plusSeconds(5 * 3600),
        tooLong.isInstanceOf[EdgeIdentityService.Refused],
        none.isInstanceOf[EdgeIdentityService.Refused],
      )
    },
    test("an edge in a partner group enrols for a certificate that names its group") {
      val edge = edgeRecord(edgeId, partnerGroup, EdgeGroupKind.Partner)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        chain <- service.enroll(edgeId, issued.token, csr)
      yield assertTrue(chain.chain.contains("BEGIN CERTIFICATE"))
    },
    test("an edge of a suspended partner is given no certificate, and keeps its token") {
      val edge = edgeRecord(edgeId, partnerGroup, EdgeGroupKind.Partner)
      val env = Env(List(edge), partnerStatus = PartnerStatus.Suspended)
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        refused <- service.enroll(edgeId, issued.token, csr).flip
        certificates <- env.certificates.all
      yield assertTrue(refused == EdgeIdentityService.PartnerSuspended, certificates.isEmpty)
    },
    test("renewal answers the edge's own request with a new certificate, and refuses another name") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        csr <- csrFor(edge)
        chain <- service.renew(edgeId, csr)
        foreign <- csrFor(edgeRecord(otherEdgeId))
        refused <- service.renew(edgeId, foreign).flip
      yield assertTrue(chain.chain.contains("BEGIN CERTIFICATE"), refused.isInstanceOf[EdgeIdentityService.Refused])
    },
  ).provide(SecureRandom.live) @@ TestAspect.silentLogging
