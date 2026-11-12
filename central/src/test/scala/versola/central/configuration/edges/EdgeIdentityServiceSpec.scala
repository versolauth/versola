package versola.central.configuration.edges

import com.nimbusds.jose.jwk.RSAKey
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
import versola.util.{CertificateSubject, EnrollmentToken, RsaKeyPair, SecureRandom, TestCertificates}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant

object EdgeIdentityServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-a")
  private val otherEdgeId = EdgeId("edge-b")
  private val partnerGroup = EdgeGroupId("partner-acme")

  private def edgeRecord(id: EdgeId, groupId: EdgeGroupId = EdgeGroup.DefaultId, kind: EdgeGroupKind = EdgeGroupKind.FirstParty) =
    EdgeRecord(
      id = id,
      publicKey = Some(Json.Obj()),
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
    val requests = Unsafe.unsafe(Ref.unsafe.make(Vector.empty[CertificateSigningRequest])(using _))

    override def sign(request: CertificateSigningRequest): Task[String] =
      validities.update(_ :+ request.validity) *> requests.update(_ :+ request) *> honestIssuer.sign(request)

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

  /** A partner's edge as it is registered: no signing key until it enrols. */
  private def keylessPartnerEdge(id: EdgeId) = partnerEdge(id).copy(publicKey = None)

  private def rsaPair(bits: Int): java.security.KeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(bits)
    generator.generateKeyPair()

  private def publicJwk(kid: String, pair: java.security.KeyPair): Json.Obj =
    RsaKeyPair(kid, pair.getPublic.asInstanceOf[RSAPublicKey], pair.getPrivate.asInstanceOf[RSAPrivateKey]).toPublicJwk

  private val pairA = rsaPair(2048)
  private val keyA = publicJwk("edge-key-a", pairA)
  private val pairB = rsaPair(2048)
  private val keyB = publicJwk("edge-key-b", pairB)

  /** Everything, the private half included: what a key pasted by mistake looks like. */
  private val privateJwk: Json.Obj =
    import zio.json.*
    RSAKey.Builder(pairA.getPublic.asInstanceOf[RSAPublicKey]).privateKey(pairA.getPrivate).keyID("leaky").build().toJSONString
      .fromJson[Json.Obj].toOption.get

  private val smallJwk = publicJwk("small", rsaPair(1024))

  private val withAddresses = TestCentralConfig.config.copy(
    publicUrls = Some(CentralConfig.PublicUrlsConfig(
      enroll = zio.http.URL.decode("https://central.example.com").toOption.get,
      mtls = zio.http.URL.decode("https://central-mtls.example.com").toOption.get,
    )),
  )

  private def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

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
        refused <- service.enroll(Some(edgeId), "not-a-token", csr).flip
        none <- env.audit.drafts
        _ <- service.enroll(Some(edgeId), issued.token, csr)
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
        _ <- service.enroll(Some(edgeId), issued.token, csr)
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
        enrolledIdentity <- service.enroll(Some(edgeId), issued.token, csr)
        chain = enrolledIdentity.chain
        again <- service.enroll(Some(edgeId), issued.token, csr).flip
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
        unregistered <- service.enroll(Some(EdgeId("nobody")), "not-a-token", "garbage").flip
        registered <- service.enroll(Some(edgeId), "not-a-token", "garbage").flip
        right <- csrFor(edge)
        registeredGoodRequest <- service.enroll(Some(edgeId), "not-a-token", right).flip
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
        withToken <- service.enroll(Some(edgeId), issued.token, "garbage").flip
        stillGood <- csrFor(edge).flatMap(csr => service.enroll(Some(edgeId), issued.token, csr))
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
        failure <- service.enroll(Some(edgeId), issued.token, csr).flip
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
        expired <- service.enroll(Some(edgeId), issued.token, csr).flip
        unknown <- service.enroll(Some(edgeId), "not-a-token", csr).flip
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
        refused <- service.enroll(Some(edgeId), issued.token, wrong).flip
        right <- csrFor(edge)
        chain <- service.enroll(Some(edgeId), issued.token, right)
      yield assertTrue(refused.isInstanceOf[EdgeIdentityService.Refused], chain.chain.contains("BEGIN CERTIFICATE"))
    },
    test("a certificate the CA returns with other names than asked is not handed to the edge") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge), lyingIssuer)
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        failure <- service.enroll(Some(edgeId), issued.token, csr).flip
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
        _ <- csrFor(own).flatMap(service.enroll(Some(edgeId), issuedOwn.token, _))
        _ <- csrFor(partner).flatMap(service.enroll(Some(otherEdgeId), issuedPartner.token, _))
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
        chain <- service.enroll(Some(edgeId), issued.token, csr)
      yield assertTrue(chain.chain.contains("BEGIN CERTIFICATE"))
    },
    test("an edge of a suspended partner is given no certificate, and keeps its token") {
      val edge = edgeRecord(edgeId, partnerGroup, EdgeGroupKind.Partner)
      val env = Env(List(edge), partnerStatus = PartnerStatus.Suspended)
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        refused <- service.enroll(Some(edgeId), issued.token, csr).flip
        certificates <- env.certificates.all
      yield assertTrue(refused == EdgeIdentityService.PartnerSuspended, certificates.isEmpty)
    },
    suite("one token (#962)")(
      test("a token says where to enrol: vtk1.<address>.<secret>, of which only the hash is kept") {
        val env = Env(List(keylessPartnerEdge(edgeId)), config = withAddresses)
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId, 24.hours)
          hashes <- env.tokens.hashes
          parsed = EnrollmentToken.parse(issued.token)
        yield assertTrue(
          issued.token.startsWith("vtk1."),
          parsed.map(_.enrollUrl) == Right("https://central.example.com"),
          parsed.exists(_.secret.nonEmpty),
          // the hash is of the whole string: that is what the edge sends back
          hashes == Set(sha256(issued.token)),
          !issued.token.contains(hashes.head),
        )
      },
      test("without a public enrollment address the token is the bare secret, as before") {
        val env = Env(List(edgeRecord(edgeId)))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
        yield assertTrue(!issued.token.startsWith("vtk1."), EnrollmentToken.parse(issued.token).isLeft)
      },
      test("a partner's edge enrols with its own public key: the token alone says which edge, the key is registered for it") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge), config = withAddresses)
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issued <- service.issueEnrollmentToken(otherEdgeId, 24.hours)
          csr <- csrFor(edge)
          identity <- service.enroll(None, issued.token, csr, Some(keyA))
          again <- csrFor(edge).flatMap(service.enroll(None, issued.token, _, Some(keyA))).flip
        yield assertTrue(
          identity.edge.id == otherEdgeId,
          identity.edge.groupId == partnerGroup,
          identity.chain.contains("BEGIN CERTIFICATE"),
          env.edgeService.setSigningKey.calls == List((otherEdgeId, keyA)),
          // single use
          again == EdgeIdentityService.InvalidToken,
        )
      },
      test("an edge registered without a key is refused an enrolment that brings none, and keeps its token") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issued <- service.issueEnrollmentToken(otherEdgeId)
          csr <- csrFor(edge)
          refused <- service.enroll(None, issued.token, csr).flip
          // the token was not spent: the same one enrols once the key is there
          enrolled <- service.enroll(None, issued.token, csr, Some(keyA))
          certificates <- env.certificates.all
        yield assertTrue(
          refused.isInstanceOf[EdgeIdentityService.Refused],
          enrolled.chain.contains("BEGIN CERTIFICATE"),
          certificates.size == 1,
        )
      },
      test("a key that is registered already is refused: the edge's own, and another edge's under any name") {
        val withKey = partnerEdge(otherEdgeId).copy(publicKey = Some(keyB))
        val keyless = keylessPartnerEdge(edgeId)
        val env = Env(List(withKey, keyless))
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issuedWithKey <- service.issueEnrollmentToken(otherEdgeId)
          issuedKeyless <- service.issueEnrollmentToken(edgeId)
          csrWithKey <- csrFor(withKey)
          csrKeyless <- csrFor(keyless)
          // an edge that has a key does not take another through an enrolment
          own <- service.enroll(None, issuedWithKey.token, csrWithKey, Some(keyA)).flip
          // and a key another edge has (the old one is just as taken) is not registered twice
          _ <- env.edgeService.getAllEdges.succeedsWith(Vector(withKey, keyless.copy(publicKey = None)))
          sameAsOther <- service.enroll(None, issuedKeyless.token, csrKeyless, Some(publicJwk("another-name", pairB))).flip
          certificates <- env.certificates.all
        yield assertTrue(
          own.isInstanceOf[EdgeIdentityService.Refused],
          sameAsOther.isInstanceOf[EdgeIdentityService.Refused],
          env.edgeService.setSigningKey.calls.isEmpty,
          certificates.isEmpty,
        )
      },
      test("a key that is not a public key central can trust is refused before the token is spent") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(otherEdgeId)
          csr <- csrFor(edge)
          leaked <- service.enroll(None, issued.token, csr, Some(privateJwk)).flip
          small <- service.enroll(None, issued.token, csr, Some(smallJwk)).flip
          garbage <- service.enroll(None, issued.token, csr, Some(Json.Obj("kty" -> Json.Str("oct")))).flip
          unnamed <- service.enroll(None, issued.token, csr, Some(Json.Obj(keyA.fields.filterNot(_._1 == "kid")*))).flip
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          fine <- service.enroll(None, issued.token, csr, Some(keyA))
        yield assertTrue(
          leaked.isInstanceOf[EdgeIdentityService.Refused],
          leaked.getMessage.contains("public"),
          small.isInstanceOf[EdgeIdentityService.Refused],
          garbage.isInstanceOf[EdgeIdentityService.Refused],
          unnamed.isInstanceOf[EdgeIdentityService.Refused],
          fine.chain.contains("BEGIN CERTIFICATE"),
        )
      },
      test("an edge enrolling with the token alone sends a request that names nothing: the signature is checked, the names are central's") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val issuer = RecordingIssuer()
        val env = Env(List(edge), issuer = issuer)
        val anonymous = CertificateSubject("CN=enrolling", "enrolling", Nil, Nil, Nil, Nil)
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issued <- service.issueEnrollmentToken(otherEdgeId)
          generated <- CertificateSubject.generate(anonymous).orDie
          enrolled <- service.enroll(None, issued.token, generated.csrPem, Some(keyA))
          requests <- issuer.requests.get
        yield assertTrue(
          enrolled.chain.contains("BEGIN CERTIFICATE"),
          // central tells the CA what to name the certificate, whatever the request carries
          requests.map(_.subjectDn) == Vector(Some(EdgeIdentityService.subjectFor(edge).distinguishedName)),
          requests.head.uris == EdgeIdentityService.subjectFor(edge).uris,
        )
      },
      test("a token-resolved enrolment still needs a request signed by its own key, in a key worth issuing for") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(otherEdgeId)
          garbage <- service.enroll(None, issued.token, "not a request", Some(keyA)).flip
          // a request signed by another key than the one it names is made by editing one: here, a request with its signature cut
          good <- CertificateSubject.generate(CertificateSubject("CN=enrolling", "enrolling", Nil, Nil, Nil, Nil)).orDie
          tampered = good.csrPem.replace(
            "\n",
            "",
          ).dropRight(60).grouped(64).mkString("-----BEGIN CERTIFICATE REQUEST-----\n", "\n", "\n-----END CERTIFICATE REQUEST-----\n")
          broken <- service.enroll(None, issued.token, tampered, Some(keyA)).flip
        yield assertTrue(garbage.isInstanceOf[EdgeIdentityService.Refused], broken.isInstanceOf[EdgeIdentityService.Refused])
      },
      test("an edge that names itself in the body is still held to naming exactly itself") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val issuer = RecordingIssuer()
        val env = Env(List(edge), issuer = issuer)
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issued <- service.issueEnrollmentToken(otherEdgeId)
          anonymous <- CertificateSubject.generate(CertificateSubject("CN=enrolling", "enrolling", Nil, Nil, Nil, Nil)).orDie
          refused <- service.enroll(Some(otherEdgeId), issued.token, anonymous.csrPem, Some(keyA)).flip
          csr <- csrFor(edge)
          enrolled <- service.enroll(Some(otherEdgeId), issued.token, csr, Some(keyA))
          requests <- issuer.requests.get
        yield assertTrue(
          refused.isInstanceOf[EdgeIdentityService.Refused],
          enrolled.chain.contains("BEGIN CERTIFICATE"),
          requests.map(_.subjectDn) == Vector(None),
        )
      },
      // Owner requirement: a partner has no way to choose first-party, and the operator's edge none to join a partner's group.
      test("a request that names the other kind of group is refused, whichever way: the token's edge decides the group") {
        val partner = keylessPartnerEdge(otherEdgeId)
        val own = edgeRecord(edgeId).copy(publicKey = None)
        val env = Env(List(partner, own))
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          partnerToken <- service.issueEnrollmentToken(otherEdgeId)
          ownToken <- service.issueEnrollmentToken(edgeId)
          // the partner edge's token, a request for the same edge in the operator's group (and the reverse), naming the edge itself
          asFirstParty <- csrFor(partner.copy(groupId = EdgeGroup.DefaultId, groupKind = EdgeGroupKind.FirstParty))
          asPartner <- csrFor(own.copy(groupId = partnerGroup, groupKind = EdgeGroupKind.Partner))
          partnerAsFirstParty <- service.enroll(Some(otherEdgeId), partnerToken.token, asFirstParty, Some(keyA)).flip
          firstPartyAsPartner <- service.enroll(Some(edgeId), ownToken.token, asPartner, Some(keyB)).flip
          // a token of one kind used with the other kind's edge id
          crossed <- service.enroll(Some(edgeId), partnerToken.token, asPartner, Some(keyA)).flip
          certificates <- env.certificates.all
        yield assertTrue(
          partnerAsFirstParty.isInstanceOf[EdgeIdentityService.Refused],
          firstPartyAsPartner.isInstanceOf[EdgeIdentityService.Refused],
          crossed == EdgeIdentityService.InvalidToken,
          certificates.isEmpty,
          env.edgeService.setSigningKey.calls.isEmpty,
        )
      },
      test("an enrolment with the token alone is issued the names of the token's edge and group, whatever group its request names") {
        val partner = keylessPartnerEdge(otherEdgeId)
        val own = edgeRecord(edgeId).copy(publicKey = None)
        val issuer = RecordingIssuer()
        val env = Env(List(partner, own), issuer = issuer)
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          partnerToken <- service.issueEnrollmentToken(otherEdgeId)
          ownToken <- service.issueEnrollmentToken(edgeId)
          // each request names the OTHER kind of group, with the right edge name
          namesFirstParty <- csrFor(partner.copy(groupId = EdgeGroup.DefaultId, groupKind = EdgeGroupKind.FirstParty))
          namesPartner <- csrFor(own.copy(groupId = partnerGroup, groupKind = EdgeGroupKind.Partner))
          _ <- service.enroll(None, partnerToken.token, namesFirstParty, Some(keyA))
          _ <- service.enroll(None, ownToken.token, namesPartner, Some(keyB))
          requests <- issuer.requests.get
        yield assertTrue(
          requests.map(_.uris) == Vector(
            List("urn:versola:edge:edge-b", "urn:versola:edge-group:partner-acme"),
            List("urn:versola:edge:edge-a", "urn:versola:edge-group:default"),
          ),
          requests.forall(_.subjectDn.isDefined),
        )
      },
      test("a token for another edge enrols nothing, with the right key and the wrong name") {
        val one = keylessPartnerEdge(edgeId)
        val other = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(one, other))
        for
          service <- env.service
          _ <- env.edgeService.setSigningKey.succeedsWith(true)
          issued <- service.issueEnrollmentToken(edgeId)
          csr <- csrFor(other)
          refused <- service.enroll(Some(otherEdgeId), issued.token, csr, Some(keyA)).flip
        yield assertTrue(
          refused == EdgeIdentityService.InvalidToken,
          env.edgeService.setSigningKey.calls.isEmpty,
        )
      },
      test("a suspended partner's edge is not enrolled and registers no key") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge), partnerStatus = PartnerStatus.Suspended)
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(otherEdgeId)
          csr <- csrFor(edge)
          refused <- service.enroll(None, issued.token, csr, Some(keyA)).flip
        yield assertTrue(refused == EdgeIdentityService.PartnerSuspended, env.edgeService.setSigningKey.calls.isEmpty)
      },
      test("a CA that fails costs the token and not the key: nothing is registered for an edge that got no certificate") {
        val edge = keylessPartnerEdge(otherEdgeId)
        val env = Env(List(edge), issuer = lyingIssuer)
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(otherEdgeId)
          csr <- csrFor(edge)
          refused <- service.enroll(None, issued.token, csr, Some(keyA)).flip
        yield assertTrue(refused.isInstanceOf[EdgeIdentityService.Refused], env.edgeService.setSigningKey.calls.isEmpty)
      },
      test("renewal with a new public key replaces the edge's key once the certificate is signed") {
        val edge = partnerEdge(otherEdgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          _ <- env.edgeService.replaceSigningKey.succeedsWith(true)
          csr <- csrFor(edge)
          identity <- service.renew(otherEdgeId, csr, None, Some(keyA))
          withoutKey <- csrFor(edge).flatMap(service.renew(otherEdgeId, _))
        yield assertTrue(
          identity.chain.contains("BEGIN CERTIFICATE"),
          withoutKey.chain.contains("BEGIN CERTIFICATE"),
          // the second renewal brought no key and left the key alone
          env.edgeService.replaceSigningKey.calls == List((otherEdgeId, keyA)),
        )
      },
      test("renewal with a key central cannot accept, or that another edge has, signs nothing and replaces nothing") {
        val edge = partnerEdge(otherEdgeId)
        val other = partnerEdge(edgeId).copy(publicKey = Some(keyB))
        val env = Env(List(edge, other))
        for
          service <- env.service
          _ <- env.edgeService.replaceSigningKey.succeedsWith(true)
          csr <- csrFor(edge)
          leaked <- service.renew(otherEdgeId, csr, None, Some(privateJwk)).flip
          taken <- service.renew(otherEdgeId, csr, None, Some(keyB)).flip
          certificates <- env.certificates.all
        yield assertTrue(
          leaked.isInstanceOf[EdgeIdentityService.Refused],
          taken.isInstanceOf[EdgeIdentityService.Refused],
          certificates.isEmpty,
          env.edgeService.replaceSigningKey.calls.isEmpty,
        )
      },
    ),
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
