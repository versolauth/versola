package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.audit.CentralAudit.CertReasons
import versola.central.audit.CertAuditChecks.{describe, detail, leafOf, onlyKnownMembers, withoutPemMaterial}
import versola.central.audit.{CentralAudit, DenialThrottle, RecordedAudit, TestCertAuditState}
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.audit.AuditOutcome
import versola.util.audit.CertOperation
import versola.util.{CertificateSubject, SecureRandom, TestCertificates}
import zio.*
import zio.json.*
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
    val audit = RecordedAudit.unsafe
    val throttle = TestCertAuditState.throttle()
    val service: URIO[SecureRandom, EdgeIdentityService] =
      for
        _ <- edgeService.find.returnsZIO(id => ZIO.succeed(edges.find(_.id == id)))
        _ <- edgeService.getAllEdges.succeedsWith(edges.toVector)
        random <- ZIO.service[SecureRandom]
      yield EdgeIdentityService.Impl(
        edgeService,
        tokens,
        certificates,
        chains,
        EdgeCertificateIssuer(issuer),
        random,
        TestCentralConfig.config.copy(edgeIdentity = policy),
        audit,
        throttle,
      )

  private def partnerEdge(id: EdgeId) = edgeRecord(id, partnerGroup, EdgeGroupKind.Partner)

  private def csrFor(edge: EdgeRecord): UIO[String] =
    CertificateSubject.generate(EdgeIdentityService.subjectFor(edge)).orDie.map(_.csrPem)

  def spec = suite("EdgeIdentityService")(
    test("enrolling an edge records cert.enrolled by that edge, after a refused enrolment that was recorded as denied") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        refused <- service.enroll(edgeId, "not-a-token", csr).flip
        denied <- env.audit.drafts
        identity <- service.enroll(edgeId, issued.token, csr)
        all <- env.audit.drafts
        recorded = all.filter(_.outcome == AuditOutcome.success)
      yield assertTrue(
        refused == EdgeIdentityService.InvalidToken,
        denied.map(d => (d.kind, d.outcome, d.reason)) == Vector((CentralAudit.CertEnrolled, AuditOutcome.denied, Some(CertReasons.TokenInvalid))),
        recorded.map(_.kind) == Vector(CentralAudit.CertEnrolled),
        recorded.head.actor == Some((CentralAudit.Actors.Edge, Some(edgeId.toString))),
        recorded.head.target == Some(("edge", edgeId.toString)),
        // which certificate: its serial, end of validity and subject, and nothing of the request or the chain
        describe(recorded.head, CertOperation.Enroll, None, leafOf(identity.chain)),
        withoutPemMaterial(recorded.head, csr, identity.chain),
        onlyKnownMembers(recorded.head),
      )
    },
    test("renewing records cert.renewed by that edge, and a request that names another edge is recorded as denied") {
      val edge = edgeRecord(edgeId)
      val env = Env(List(edge, edgeRecord(otherEdgeId)))
      for
        service <- env.service
        issued <- service.issueEnrollmentToken(edgeId)
        csr <- csrFor(edge)
        _ <- service.enroll(edgeId, issued.token, csr)
        refused <- csrFor(edgeRecord(otherEdgeId)).flatMap(service.renew(edgeId, _)).flip
        renewalCsr <- csrFor(edge)
        renewed <- service.renew(edgeId, renewalCsr, None)
        all <- env.audit.drafts
        recorded = all.filter(_.outcome == AuditOutcome.success)
      yield assertTrue(
        refused.isInstanceOf[EdgeIdentityService.Refused],
        all.map(d => (d.kind, d.outcome, d.reason)) == Vector(
          (CentralAudit.CertEnrolled, AuditOutcome.success, None),
          (CentralAudit.CertRenewed, AuditOutcome.denied, Some(CertReasons.RequestMismatch)),
          (CentralAudit.CertRenewed, AuditOutcome.success, None),
        ),
        recorded.map(_.kind) == Vector(CentralAudit.CertEnrolled, CentralAudit.CertRenewed),
        recorded.last.actor == Some((CentralAudit.Actors.Edge, Some(edgeId.toString))),
        describe(recorded.last, CertOperation.Renew, None, leafOf(renewed.chain)),
        withoutPemMaterial(recorded.last, renewalCsr, renewed.chain),
        onlyKnownMembers(recorded.last),
      )
    },
    suite("refused and failed attempts are recorded")(
      test("a token that is not good is denied for a registered edge, with no subject: the request is not read") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          csr <- csrFor(edge)
          _ <- service.enroll(edgeId, "not-a-token", csr).flip
          recorded <- env.audit.drafts
        yield assertTrue(
          recorded.map(d => (d.kind, d.outcome, d.reason)) == Vector((
            CentralAudit.CertEnrolled,
            AuditOutcome.denied,
            Some(CertReasons.TokenInvalid),
          )),
          recorded.head.actor == Some((CentralAudit.Actors.Edge, Some(edgeId.toString))),
          recorded.head.target == Some(("edge", edgeId.toString)),
          detail(recorded.head, "operation") == Some("enroll"),
          detail(recorded.head, "subject").isEmpty,
          detail(recorded.head, "serial").isEmpty,
          onlyKnownMembers(recorded.head),
          // neither the token nor the request is in the event
          withoutPemMaterial(recorded.head, csr),
          !recorded.head.details.toJson.toJson.contains("not-a-token"),
        )
      },
      test("an edge that is not registered is not recorded, and the throttle keeps nothing for it") {
        val env = Env(List(edgeRecord(edgeId)))
        for
          service <- env.service
          _ <- ZIO.foreachDiscard(1 to 20)(i => service.enroll(EdgeId(s"nobody-$i"), "not-a-token", "garbage").flip)
          recorded <- env.audit.drafts
          kept <- env.throttle.tracked
        yield assertTrue(recorded.isEmpty, kept.isEmpty)
      },
      test("guesses at the token of one edge are one event a minute, whatever their number, and each edge has its own") {
        val env = Env(List(edgeRecord(edgeId), edgeRecord(otherEdgeId)))
        for
          service <- env.service
          _ <- ZIO.foreachDiscard(1 to 50)(i => service.enroll(edgeId, s"guess-$i", "garbage").flip)
          afterFlood <- env.audit.drafts
          _ <- service.enroll(otherEdgeId, "guess", "garbage").flip
          withOther <- env.audit.drafts
          _ <- TestClock.adjust(DenialThrottle.Interval + 1.second)
          _ <- service.enroll(edgeId, "guess", "garbage").flip
          later <- env.audit.drafts
          kept <- env.throttle.tracked
        yield assertTrue(
          afterFlood.size == 1,
          withOther.map(_.target) == Vector(Some(("edge", edgeId.toString)), Some(("edge", otherEdgeId.toString))),
          later.size == 3,
          // an entry is dropped once its minute is over, so what is kept is only the edges refused within the last one
          kept == Set(edgeId.toString),
        )
      },
      test("a token already spent is denied the same way") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          csr <- csrFor(edge)
          _ <- service.enroll(edgeId, issued.token, csr)
          _ <- service.enroll(edgeId, issued.token, csr).flip
          recorded <- env.audit.drafts
        yield assertTrue(recorded.map(_.reason) == Vector(None, Some(CertReasons.TokenInvalid)))
      },
      test("a request that names something else is denied with the subject the edge would have had, and the token stays good") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          wrong <- csrFor(edgeRecord(edgeId, partnerGroup))
          _ <- service.enroll(edgeId, issued.token, wrong).flip
          recorded <- env.audit.drafts
          _ <- csrFor(edge).flatMap(service.enroll(edgeId, issued.token, _))
        yield assertTrue(
          recorded.map(d => (d.outcome, d.reason)) == Vector((AuditOutcome.denied, Some(CertReasons.RequestMismatch))),
          detail(recorded.head, "subject").isDefined,
          withoutPemMaterial(recorded.head, wrong),
          // the reason is a code: what the request got wrong is not repeated
          !recorded.head.details.toJson.toJson.contains("subject is not the one"),
        )
      },
      test("renewing for an edge that is not registered, with a request for another edge, or past the chain's limit, is denied") {
        val partner = partnerEdge(edgeId)
        val env = Env(List(partner), policy = CentralConfig.EdgeIdentityConfig(maxAge = 100.days, autoExtend = false))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          csr <- csrFor(partner)
          _ <- service.enroll(edgeId, issued.token, csr)
          _ <- csrFor(edgeRecord(otherEdgeId)).flatMap(service.renew(edgeId, _, None)).flip
          _ <- csrFor(edgeRecord(edgeId)).flatMap(service.renew(edgeId, _, None)).flip
          _ <- service.renew(EdgeId("nobody"), csr, None).flip
          _ <- TestClock.adjust(101.days)
          _ <- csrFor(partner).flatMap(service.renew(edgeId, _, None)).flip
          recorded <- env.audit.drafts
          denied = recorded.filter(_.outcome == AuditOutcome.denied)
        yield assertTrue(
          denied.map(_.reason) == Vector(
            Some(CertReasons.RequestMismatch),
            Some(CertReasons.RequestMismatch),
            Some(CertReasons.EdgeUnknown),
            Some(CertReasons.ChainLimit),
          ),
          denied.forall(_.kind == CentralAudit.CertRenewed),
          denied.forall(d => detail(d, "operation") == Some("renew")),
          // the chain limit's reason says what to do, with the edge's id: none of it is in the event
          !denied.exists(_.details.toJson.toJson.contains("/configuration/edges/extend")),
        )
      },
      suite("an issuer that does not answer")(
        List(
          (
            "is unreachable",
            ClientCertificateIssuer.IssuerFailed.unreachable(java.net.ConnectException("connection refused: ca.internal:9000")),
            CertReasons.IssuerUnreachable,
          ),
          (
            "refuses",
            ClientCertificateIssuer.IssuerFailed.refused("step-ca refused the signing request with 400: {\"csr\":\"secret-looking text\"}"),
            CertReasons.IssuerRefused,
          ),
          (
            "does not answer in time",
            ClientCertificateIssuer.IssuerFailed.timedOut("cert-manager did not sign request x within 60 seconds"),
            CertReasons.IssuerTimeout,
          ),
          ("answers what is no certificate", ClientCertificateIssuer.IssuerFailed.invalidAnswer("not json: <html>"), CertReasons.IssuerInvalidAnswer),
          ("fails in a way nothing names", RuntimeException("boom: stack trace here"), CertReasons.IssuerError),
          ("is not configured", ClientCertificateIssuer.NotConfigured, CertReasons.CaNotConfigured),
        ).map: (what, error, reason) =>
          test(s"an issuer that $what fails an enrolment and a renewal, as $reason, and says nothing of what it answered") {
            val edge = edgeRecord(edgeId)
            val failing: ClientCertificateIssuer = (_: CertificateSigningRequest) => ZIO.fail(error)
            val env = Env(List(edge), failing)
            for
              service <- env.service
              issued <- service.issueEnrollmentToken(edgeId)
              csr <- csrFor(edge)
              enrolment <- service.enroll(edgeId, issued.token, csr).exit
              renewal <- service.renew(edgeId, csr, None).exit
              recorded <- env.audit.drafts
              text = recorded.map(_.details.toJson.toJson).mkString + recorded.flatMap(_.reason).mkString
            yield assertTrue(
              enrolment.isFailure,
              renewal.isFailure,
              recorded.map(d => (d.kind, d.outcome, d.reason)) == Vector(
                (CentralAudit.CertEnrolled, AuditOutcome.failure, Some(reason)),
                (CentralAudit.CertRenewed, AuditOutcome.failure, Some(reason)),
              ),
              recorded.forall(d => detail(d, "serial").isEmpty && detail(d, "notAfter").isEmpty && detail(d, "subject").nonEmpty),
              recorded.forall(onlyKnownMembers),
              !text.contains("secret-looking") && !text.contains("stack trace") && !text.contains("ca.internal") && !text.contains("<html>"),
            )
          },
      ),
      test("a certificate the CA returns with other names than asked is a failure, certificate_rejected") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge), lyingIssuer)
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          csr <- csrFor(edge)
          _ <- service.enroll(edgeId, issued.token, csr).flip
          recorded <- env.audit.drafts
        yield assertTrue(
          recorded.map(d => (d.kind, d.outcome, d.reason)) == Vector((
            CentralAudit.CertEnrolled,
            AuditOutcome.failure,
            Some(CertReasons.CertificateRejected),
          )),
        )
      },
      test("every reason an edge's attempt can have is in the closed vocabulary") {
        val reasons = Set(
          CertReasons.TokenInvalid,
          CertReasons.RequestMismatch,
          CertReasons.EdgeUnknown,
          CertReasons.ChainLimit,
          CertReasons.CertificateRejected,
          CertReasons.IssuerUnreachable,
        )
        assertTrue(reasons.subsetOf(CertReasons.all))
      },
    ),
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
    suite("with auto-extend off, for a partner's edge")(
      test("the operator's own edges are extended whatever the setting says") {
        val edge = edgeRecord(edgeId)
        val env = Env(List(edge), policy = CentralConfig.EdgeIdentityConfig(maxAge = 100.days, autoExtend = false))
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(edgeId)
          enrolled <- csrFor(edge).flatMap(service.enroll(edgeId, issued.token, _))
          _ <- TestClock.adjust(400.days)
          late <- csrFor(edge).flatMap(service.renew(edgeId, _))
          ends <- service.chainEnds
        yield assertTrue(enrolled.chainEndsAt.isEmpty, late.chain.contains("BEGIN CERTIFICATE"), late.chainEndsAt.isEmpty, ends.isEmpty)
      },
      test("the edge is told when its chain ends, in the answer to the enrollment and to every renewal") {
        val edge = partnerEdge(edgeId)
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
        val edge = partnerEdge(edgeId)
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
      val edge = partnerEdge(edgeId)
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
