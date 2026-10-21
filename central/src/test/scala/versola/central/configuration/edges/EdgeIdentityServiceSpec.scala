package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer}
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

  private class Env(edges: List[EdgeRecord], issuer: ClientCertificateIssuer = honestIssuer):
    val edgeService = stub[EdgeService]
    val tokens = InMemoryTokens(Unsafe.unsafe(Ref.unsafe.make(Map.empty[String, (EdgeId, Instant, Boolean)])(using _)))
    val certificates = InMemoryCertificates(Unsafe.unsafe(Ref.unsafe.make(Vector.empty[(EdgeId, String)])(using _)))
    val service: URIO[SecureRandom, EdgeIdentityService] =
      for
        _ <- edgeService.find.returnsZIO(id => ZIO.succeed(edges.find(_.id == id)))
        random <- ZIO.service[SecureRandom]
      yield EdgeIdentityService.Impl(edgeService, tokens, certificates, EdgeCertificateIssuer(issuer), random, TestCentralConfig.config)

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
        chain <- service.enroll(edgeId, issued.token, csr)
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
      yield assertTrue(withToken.isInstanceOf[EdgeIdentityService.Refused], stillGood.contains("BEGIN CERTIFICATE"))
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
      yield assertTrue(refused.isInstanceOf[EdgeIdentityService.Refused], chain.contains("BEGIN CERTIFICATE"))
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
    test("an edge in a partner group enrols for a certificate that names its group") {
      val edge = edgeRecord(edgeId, partnerGroup, EdgeGroupKind.Partner)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        chain <- service.enroll(edgeId, issued.token, csr)
      yield assertTrue(chain.contains("BEGIN CERTIFICATE"))
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
      yield assertTrue(chain.contains("BEGIN CERTIFICATE"), refused.isInstanceOf[EdgeIdentityService.Refused])
    },
  ).provide(SecureRandom.live) @@ TestAspect.silentLogging
