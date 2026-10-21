package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer}
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

    def hashes: UIO[Set[String]] = state.get.map(_.keySet)

  private class InMemoryChains(state: Ref[Map[EdgeId, Instant]]) extends EdgeIdentityChainRepository:
    override def start(edgeId: EdgeId, at: Instant): Task[Unit] = state.update(_.updated(edgeId, at))

    override def startedAt(edgeId: EdgeId): Task[Option[Instant]] = state.get.map(_.get(edgeId))

    override def all: Task[Map[EdgeId, Instant]] = state.get

  private class InMemoryCertificates(state: Ref[Vector[(EdgeId, String)]]) extends EdgeCertificateRepository:
    override def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit] =
      state.update(_ :+ (edgeId -> serial))

    def all: UIO[Vector[(EdgeId, String)]] = state.get

  /** Issues what was asked, the way a CA that signs the request as it is does. */
  private val honestIssuer: ClientCertificateIssuer =
    (request: CertificateSigningRequest) =>
      ZIO.succeed(TestCertificates.generate(subject = s"CN=${request.commonName}", uris = request.uris).certificatePem)

  /** Issues a certificate that names a different edge than the one asked for. */
  private val lyingIssuer: ClientCertificateIssuer =
    (_: CertificateSigningRequest) =>
      ZIO.succeed(TestCertificates.generate(uris = List(EdgeCertificate.uriFor(otherEdgeId))).certificatePem)

  private class Env(
      edges: List[EdgeRecord],
      issuer: ClientCertificateIssuer = honestIssuer,
      policy: CentralConfig.EdgeIdentityConfig = CentralConfig.EdgeIdentityConfig(),
  ):
    val edgeService = stub[EdgeService]
    val tokens = InMemoryTokens(Unsafe.unsafe(Ref.unsafe.make(Map.empty[String, (EdgeId, Instant, Boolean)])(using _)))
    val certificates = InMemoryCertificates(Unsafe.unsafe(Ref.unsafe.make(Vector.empty[(EdgeId, String)])(using _)))
    val chains = InMemoryChains(Unsafe.unsafe(Ref.unsafe.make(Map.empty[EdgeId, Instant])(using _)))
    val service: URIO[SecureRandom, EdgeIdentityService] =
      for
        _ <- edgeService.find.returnsZIO(id => ZIO.succeed(edges.find(_.id == id)))
        random <- ZIO.service[SecureRandom]
      yield EdgeIdentityService.Impl(
        edgeService,
        tokens,
        certificates,
        chains,
        EdgeCertificateIssuer(issuer),
        random,
        TestCentralConfig.config.copy(edgeIdentity = policy),
      )

  private def csrFor(edge: EdgeRecord): UIO[String] =
    CertificateSubject.generate(EdgeIdentityService.subjectFor(edge)).orDie.map(_.csrPem)

  def spec = suite("EdgeIdentityService")(
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
    test("by default an edge renews its way along past the maximum age, and central extends the chain itself") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        enrolled <- csrFor(edge).flatMap(service.enroll(edgeId, issued.token, _))
        _ <- TestClock.adjust(400.days)
        late <- csrFor(edge).flatMap(service.renew(edgeId, _))
        later <- TestClock.adjust(400.days) *> csrFor(edge).flatMap(service.renew(edgeId, _))
        ends <- service.chainEnds
      yield assertTrue(
        enrolled.chainEndsAt.isEmpty,
        late.chain.contains("BEGIN CERTIFICATE"),
        late.chainEndsAt.isEmpty,
        later.chain.contains("BEGIN CERTIFICATE"),
        ends.isEmpty,
      )
    },
    suite("with auto-extend off")(
      test("the edge is told when its chain ends, in the answer to the enrollment and to every renewal") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge), policy = CentralConfig.EdgeIdentityConfig(maxAge = 100.days, autoExtend = false))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          now <- Clock.instant
          enrolled <- csrFor(edge).flatMap(service.enroll(edgeId, issued.token, _))
          _ <- TestClock.adjust(30.days)
          renewed <- csrFor(edge).flatMap(service.renew(edgeId, _))
          ends <- service.chainEnds
        yield assertTrue(
          enrolled.chainEndsAt == Some(now.plusSeconds(100 * 86400L)),
          renewed.chainEndsAt == enrolled.chainEndsAt,
          ends == Map(edgeId -> now.plusSeconds(100 * 86400L)),
        )
      },
      test("at the maximum age renewal stops, with what to do, and an operator's extension makes it go on") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge), policy = CentralConfig.EdgeIdentityConfig(maxAge = 100.days, autoExtend = false))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          _ <- csrFor(edge).flatMap(service.enroll(edgeId, issued.token, _))
          _ <- TestClock.adjust(101.days)
          refused <- csrFor(edge).flatMap(service.renew(edgeId, _)).flip
          _ <- service.extend(edgeId)
          extended <- csrFor(edge).flatMap(service.renew(edgeId, _))
          ends <- service.chainEnds
          now <- Clock.instant
        yield assertTrue(
          refused.isInstanceOf[EdgeIdentityService.Refused],
          refused.getMessage.contains("/configuration/edges/extend"),
          refused.getMessage.contains("enrol it again"),
          extended.chain.contains("BEGIN CERTIFICATE"),
          ends == Map(edgeId -> now.plusSeconds(100 * 86400L)),
        )
      },
      test("an edge that is not registered cannot be extended") {
        val env = Env(Nil, policy = CentralConfig.EdgeIdentityConfig(autoExtend = false))
        for
          service <- env.service
          failure <- service.extend(edgeId).flip
        yield assertTrue(failure.isInstanceOf[EdgeIdentityService.Refused])
      },
    ),
    test("an edge that has no chain recorded, enrolled before chains were kept, starts one with its first renewal") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge), policy = CentralConfig.EdgeIdentityConfig(maxAge = 100.days, autoExtend = false))
      for
        service <- env.service
        now <- Clock.instant
        renewed <- csrFor(edge).flatMap(service.renew(edgeId, _))
      yield assertTrue(renewed.chainEndsAt == Some(now.plusSeconds(100 * 86400L)))
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
