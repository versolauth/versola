package versola.central.configuration.clients.certificates

import org.bouncycastle.asn1.x509.{Extension, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.audit.CertAuditChecks.{detail, onlyKnownMembers, withoutPemMaterial}
import versola.central.audit.{CentralAudit, RecordedAudit}
import versola.central.configuration.clients.*
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, UpdateClientRequest}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.CertificateSubject
import versola.util.audit.{ActorType, AuditContext, AuditDraft, AuditEvents, AuditOutcome}
import versola.util.{Patch, PrivateClientCertificate, TestCertificates}
import zio.*
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

object ClientCertificateServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("mobile-app")
  private val registeredAt = Instant.parse("2026-02-01T09:00:00Z")
  private val dnsName = "mobile-app.clients.versola.test"
  private val auth = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, dnsName)

  private val ca = TestCertificates.generate(subject = "CN=test-client-ca", ca = true)

  /** A CA that signs whatever request it is handed, as the real ones do for an authorised one:
    * the subject and SANs come from the request, the lifetime from `validity`. */
  private class FakeCa(addedUri: Option[String] = None) extends ClientCertificateIssuer:
    var requests: List[CertificateSigningRequest] = Nil

    override def sign(request: CertificateSigningRequest): Task[String] =
      ZIO.attempt:
        requests = requests :+ request
        val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(request.csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
        val now = java.lang.System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
          ca.certificate,
          BigInteger.valueOf(now),
          Date(now - 60_000),
          Date(now + request.validity.toMillis),
          csr.getSubject,
          csr.getPublicKey,
        )
        val requested = Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName)))
          .map(san => GeneralNames.getInstance(san.getParsedValue).getNames.toList).getOrElse(Nil)
        // A CA that adds a name of its own to what it signs, as a misconfigured template would.
        val names = requested ++ addedUri.map(uri =>
          org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier, uri),
        )
        if names.nonEmpty then builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toArray))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ClientCertificateRequests.pem("CERTIFICATE", certificate.getEncoded)

  private class Issuances extends ClientCertificateIssuanceRepository:
    var rows: Map[ClientId, ClientCertificateIssuance] = Map.empty
    def upsert(issuance: ClientCertificateIssuance) = ZIO.succeed { rows += issuance.clientId -> issuance }
    def replace(issuance: ClientCertificateIssuance) =
      ZIO.succeed(rows.contains(issuance.clientId) && { rows += issuance.clientId -> issuance; true })
    def find(clientId: ClientId) = ZIO.succeed(rows.get(clientId))
    def delete(clientId: ClientId) = ZIO.succeed { rows -= clientId }
    def expiringBefore(deadline: Instant) =
      ZIO.succeed(rows.values.filter(_.notAfter.isBefore(deadline)).toVector.sortBy(_.notAfter))

  private class Enrollments extends EdgeCertificateEnrollmentRepository:
    var enrolled: Set[ClientId] = Set.empty
    var issued: List[(ClientId, String, EdgeId)] = Nil
    def enroll(clientId: ClientId, at: Instant) = ZIO.succeed { enrolled += clientId }
    def isEnrolled(clientId: ClientId) = ZIO.succeed(enrolled.contains(clientId))
    def enrolledClients = ZIO.succeed(enrolled)
    def recordIssued(clientId: ClientId, serial: String, at: Instant, edgeId: EdgeId) =
      ZIO.succeed { issued = issued :+ ((clientId, serial, edgeId)) }
    def delete(clientId: ClientId) = ZIO.succeed { enrolled -= clientId }

  private val redirectUri = versola.util.RedirectUri("https://app.example.com/callback")

  private val createRequest = CreateClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = Map("en" -> "Mobile"),
    redirectUris = Set(redirectUri),
    allowedScopes = Set.empty,
    permissions = Set.empty,
    accessTokenTtl = 300,
    refreshTokenTtl = None,
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
    dpopBoundAccessTokens = true,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(auth),
    certificateBoundAccessTokens = false,
    jwks = None,
    generateJwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = true,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = None,
    applicationType = Some(ApplicationType.native),
    issueEdgeClientCertificate = true,
    enrollEdgeClientCertificate = false,
    themeOverrides = None,
    estEnrollment = false,
  )

  private def record =
    OAuthClientRecord(
      id = clientId,
      tenantId = tenantId,
      clientName = Map("en" -> "Mobile"),
      redirectUris = Set(redirectUri),
      scope = Set.empty,
      secret = None,
      previousSecret = None,
      accessTokenTtl = 5.minutes,
      refreshTokenTtl = 7776000.seconds,
      permissions = Set.empty,
      theme = "",
      authFlow = None,
      otpTemplateId = "default",
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens = true,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = AuthMethod.tls_client_auth,
      mtlsAuth = Some(auth),
      certificateBoundAccessTokens = false,
      jwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = true,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = None,
      createdAt = registeredAt,
      applicationType = ApplicationType.native,
      themeOverrides = None,
    )

  private val config =
    TestCentralConfig.config.copy(clientCertificates =
      Some(
        CentralConfig.ClientCertificatesConfig(validity = 14.days, renewBefore = 4.days),
      ),
    )

  private def subjectOf(certificate: PrivateClientCertificate) =
    certificate.material.toOption.get.subjectValues("san_dns")

  private val untouchedUpdate = UpdateClientRequest(
    clientId = clientId,
    clientName = None,
    redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
    scope = PatchClientScope(Set.empty, Set.empty),
    permissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl = None,
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
    otpTemplateId = None,
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = None,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = None,
    dpopSigningAlgs = None,
    dpopMinRsaKeySize = None,
    authMethod = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = None,
    jwks = None,
    requireSignedRequestObject = None,
    requirePushedAuthorizationRequests = None,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    applicationType = None,
    themeOverrides = None,
  )

  def spec = suite("ClientCertificateService")(
    test("registers a client with a certificate the CA issued for its mtlsAuth") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val fakeCa = FakeCa()
      val service = ClientCertificateService.Impl(fakeCa, clients, issuances, Enrollments(), config, AuditEvents.noop)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- service.register(createRequest)
        sent = clients.registerClient.calls.head._1
      yield assertTrue(
        !sent.issueEdgeClientCertificate,
        sent.edgeClientCertificate.exists(c => subjectOf(c) == Set(dnsName)),
        fakeCa.requests.map(_.dnsNames) == List(List(dnsName)),
        issuances.rows.get(clientId).exists(_.notAfter.isAfter(Instant.now().plusSeconds(13 * 24 * 3600L))),
      )
    },
    test("a certificate issued at registration is recorded as cert.enrolled by the administrator, with its serial, validity and subject") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val audit = RecordedAudit.unsafe
      val service = ClientCertificateService.Impl(FakeCa(), clients, issuances, Enrollments(), config, audit)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- service.register(createRequest)
        recorded <- audit.drafts
        issuance = issuances.rows(clientId)
        sent = clients.registerClient.calls.head._1
        material = sent.edgeClientCertificate.get.material.toOption.get
      yield assertTrue(
        recorded.map(_.kind) == Vector(CentralAudit.CertEnrolled),
        recorded.head.outcome == AuditOutcome.success,
        recorded.head.target == Some(("client", clientId.toString)),
        detail(recorded.head, "operation") == Some("issue"),
        detail(recorded.head, "clientId") == Some(clientId.toString),
        detail(recorded.head, "serial") == Some(issuance.serial),
        detail(recorded.head, "notAfter") == Some(issuance.notAfter.toString),
        detail(recorded.head, "subject") == Some(material.subjectDn),
        withoutPemMaterial(recorded.head, material.privateKeyPem, material.certificateChain),
        onlyKnownMembers(recorded.head),
      )
    },
    test("the administrator whose request registered the client is the actor of cert.enrolled") {
      val clients = stub[OAuthClientService]
      val audit = RecordedAudit.unsafe
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, audit)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- AuditContext.reset(AuditContext.State.empty):
          AuditContext.setActor(CentralAudit.Actors.Admin, Some("admin-1")) *> service.register(createRequest)
        recorded <- audit.drafts
      yield assertTrue(recorded.map(_.actor) == Vector(Some((CentralAudit.Actors.Admin, Some("admin-1")))))
    },
    test("a registration that fails records no certificate event") {
      val clients = stub[OAuthClientService]
      val audit = RecordedAudit.unsafe
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, audit)
      for
        _ <- clients.registerClient.failsWith(RuntimeException("database down"))
        exit <- service.register(createRequest).exit
        recorded <- audit.drafts
      yield assertTrue(exit.isFailure, recorded.isEmpty)
    },
    test("passes a registration that asks for no certificate straight through, issuing nothing") {
      val clients = stub[OAuthClientService]
      val fakeCa = FakeCa()
      val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), Enrollments(), config, AuditEvents.noop)
      val plain = createRequest.copy(issueEdgeClientCertificate = false, authMethod = AuthMethod.none, mtlsAuth = None)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- service.register(plain)
      yield assertTrue(fakeCa.requests.isEmpty, clients.registerClient.calls.head._1 == plain)
    },
    test("refuses to issue when the request also supplies a certificate") {
      val clients = stub[OAuthClientService]
      val supplied = PrivateClientCertificate(TestCertificates.generate(dnsName = Some(dnsName)).bundle)
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
      for exit <- service.register(createRequest.copy(edgeClientCertificate = Some(supplied))).exit
      yield assertTrue(exit.isFailure, clients.registerClient.calls.isEmpty)
    },
    test("registers by CN=<client>,OU=<tenant>,O=Versola when the request names no mtlsAuth") {
      val clients = stub[OAuthClientService]
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
      val dn = "CN=mobile-app,OU=tenant-a,O=Versola"
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- service.register(createRequest.copy(mtlsAuth = None))
        sent = clients.registerClient.calls.head._1
      yield assertTrue(
        sent.mtlsAuth == Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, dn)),
        sent.edgeClientCertificate.exists(_.material.toOption.exists(_.subjectValues("subject_dn") == Set(dn))),
      )
    },
    test("refuses to issue for a method that reads no certificate") {
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], Issuances(), Enrollments(), config, AuditEvents.noop)
      for exit <- service.register(createRequest.copy(authMethod = AuthMethod.client_secret)).exit
      yield assertTrue(exit.isFailure)
    },
    // `urn:versola:edge:<id>` names an edge to central's mutual-TLS listener; the CA that signs a client's
    // certificate may be one that listener trusts, so no client is issued a certificate carrying it.
    test("refuses to issue a certificate carrying the URI name that makes it an edge's") {
      val clients = stub[OAuthClientService]
      val fakeCa = FakeCa()
      val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), Enrollments(), config, AuditEvents.noop)
      val asEdge = createRequest.copy(mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, "urn:versola:edge:edge-default")))
      for exit <- service.register(asEdge).exit
      yield assertTrue(exit.isFailure, fakeCa.requests.isEmpty, clients.registerClient.calls.isEmpty)
    },
    test("says so when central has no CA to issue from") {
      val service = ClientCertificateService.Impl(
        ClientCertificateIssuer.notConfigured,
        stub[OAuthClientService],
        Issuances(),
        Enrollments(),
        config,
        AuditEvents.noop,
      )
      for exit <- service.register(createRequest).exit
      yield assertTrue(exit.isFailure, exit.toString.contains("none configured"))
    },
    test("a renewal that came due is recorded as cert.renewed by the system") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val audit = RecordedAudit.unsafe
      val service = ClientCertificateService.Impl(FakeCa(), clients, issuances, Enrollments(), config, audit)
      for
        now <- Clock.instant
        _ = issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", now.plusSeconds(3600), now.minusSeconds(86400)))
        _ <- clients.getAllClients.succeedsWith(Vector(record))
        _ <- clients.updateClient.succeedsWith(())
        _ <- service.renewDue
        recorded <- audit.drafts
        renewed = clients.updateClient.calls.head._1.edgeClientCertificate.collect { case Patch.Modified(c) => c }.flatMap(_.material.toOption).get
      yield assertTrue(
        recorded.map(_.kind) == Vector(CentralAudit.CertRenewed),
        recorded.head.actor == Some((ActorType.system, None)),
        recorded.head.target == Some(("client", clientId.toString)),
        detail(recorded.head, "operation") == Some("renew"),
        detail(recorded.head, "clientId") == Some(clientId.toString),
        detail(recorded.head, "serial") == issuances.rows.get(clientId).map(_.serial),
        detail(recorded.head, "notAfter") == issuances.rows.get(clientId).map(_.notAfter.toString),
        detail(recorded.head, "subject") == Some(renewed.subjectDn),
        withoutPemMaterial(recorded.head, renewed.privateKeyPem, renewed.certificateChain),
        onlyKnownMembers(recorded.head),
      )
    },
    test("renews only the certificates inside the renewal window") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val other = ClientId("other-app")
      val service = ClientCertificateService.Impl(FakeCa(), clients, issuances, Enrollments(), config, AuditEvents.noop)
      for
        // The test clock, not the wall clock: the service reads `Clock.instant`.
        now <- Clock.instant
        _ = issuances.rows = Map(
          clientId -> ClientCertificateIssuance(clientId, "01", now.plusSeconds(3600), now.minusSeconds(86400)),
          other -> ClientCertificateIssuance(other, "02", now.plusSeconds(10 * 24 * 3600L), now),
        )
        _ <- clients.getAllClients.succeedsWith(Vector(record))
        _ <- clients.updateClient.succeedsWith(())
        renewed <- service.renewDue
        update = clients.updateClient.calls.head._1
      yield assertTrue(
        renewed == 1,
        clients.updateClient.calls.size == 1,
        update.clientId == clientId,
        update.edgeClientCertificate.exists:
          case Patch.Modified(c) => subjectOf(c) == Set(dnsName)
          case _ => false,
        issuances.rows(clientId).serial != "01",
        issuances.rows(other).serial == "02",
      )
    },
    test("a failed renewal leaves the old certificate in place") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val failing = new ClientCertificateIssuer:
        def sign(request: CertificateSigningRequest) = ZIO.fail(RuntimeException("CA down"))
      val service = ClientCertificateService.Impl(failing, clients, issuances, Enrollments(), config, AuditEvents.noop)
      for
        now <- Clock.instant
        _ = issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", now.plusSeconds(60), now))
        _ <- clients.getAllClients.succeedsWith(Vector(record))
        renewed <- service.renewDue
      yield assertTrue(renewed == 0, clients.updateClient.calls.isEmpty, issuances.rows(clientId).serial == "01")
    },
    test("a certificate an operator replaced while the CA was answering is not taken back") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      // The operator's replacement lands while the CA is signing: the row is gone by the time it answers.
      val slowCa = new ClientCertificateIssuer:
        private val inner = FakeCa()
        def sign(request: CertificateSigningRequest) =
          issuances.delete(clientId) *> inner.sign(request)
      val service = ClientCertificateService.Impl(slowCa, clients, issuances, Enrollments(), config, AuditEvents.noop)
      for
        now <- Clock.instant
        _ = issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", now.plusSeconds(60), now))
        _ <- clients.getAllClients.succeedsWith(Vector(record))
        renewed <- service.renewDue
      yield assertTrue(renewed == 1, clients.updateClient.calls.isEmpty, !issuances.rows.contains(clientId))
    },
    test("takes the registration back when the issuance cannot be recorded, so it can be retried") {
      val clients = stub[OAuthClientService]
      val failing = new Issuances:
        override def upsert(issuance: ClientCertificateIssuance) = ZIO.fail(RuntimeException("db down"))
      val service = ClientCertificateService.Impl(FakeCa(), clients, failing, Enrollments(), config, AuditEvents.noop)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- clients.deleteClient.succeedsWith(())
        exit <- service.register(createRequest).exit.fork.flatMap(fiber => TestClock.adjust(5.seconds) *> fiber.join)
      yield assertTrue(exit.isFailure, clients.deleteClient.calls == List(clientId))
    },
    suite("an edge that generates its own key (#463)")(
      test("registration records the enrolment and stores no certificate and no key") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        val fakeCa = FakeCa()
        val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), enrollments, config, AuditEvents.noop)
        for
          _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
          _ <- service.register(createRequest.copy(issueEdgeClientCertificate = false, enrollEdgeClientCertificate = true))
          sent = clients.registerClient.calls.head._1
        yield assertTrue(
          enrollments.enrolled == Set(clientId),
          sent.edgeClientCertificate.isEmpty,
          sent.enrollEdgeClientCertificate,
          sent.mtlsAuth == Some(auth),
          fakeCa.requests.isEmpty,
        )
      },
      test("with no mtlsAuth the client is registered by CN=<client>,OU=<tenant>,O=Versola") {
        val clients = stub[OAuthClientService]
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
        for
          _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
          _ <- service.register(createRequest.copy(issueEdgeClientCertificate = false, enrollEdgeClientCertificate = true, mtlsAuth = None))
        yield assertTrue(
          clients.registerClient.calls.head._1.mtlsAuth ==
            Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=mobile-app,OU=tenant-a,O=Versola")),
        )
      },
      test("is refused beside issueEdgeClientCertificate, a supplied certificate, or a method that reads none") {
        val clients = stub[OAuthClientService]
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
        val enrolling = createRequest.copy(issueEdgeClientCertificate = false, enrollEdgeClientCertificate = true)
        val supplied = PrivateClientCertificate(TestCertificates.generate(dnsName = Some(dnsName)).bundle)
        for
          both <- service.register(enrolling.copy(issueEdgeClientCertificate = true)).exit
          withCertificate <- service.register(enrolling.copy(edgeClientCertificate = Some(supplied))).exit
          withSecret <- service.register(enrolling.copy(authMethod = AuthMethod.client_secret)).exit
        yield assertTrue(both.isFailure, withCertificate.isFailure, withSecret.isFailure, clients.registerClient.calls.isEmpty)
      },
      test("takes the registration back when the enrolment cannot be recorded") {
        val clients = stub[OAuthClientService]
        val failing = new Enrollments:
          override def enroll(clientId: ClientId, at: Instant) = ZIO.fail(RuntimeException("db down"))
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), failing, config, AuditEvents.noop)
        for
          _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
          _ <- clients.deleteClient.succeedsWith(())
          exit <- service.register(createRequest.copy(issueEdgeClientCertificate = false, enrollEdgeClientCertificate = true))
            .exit.fork.flatMap(fiber => TestClock.adjust(5.seconds) *> fiber.join)
        yield assertTrue(exit.isFailure, clients.deleteClient.calls == List(clientId))
      },
      test("signs the request an enrolled client's edge made for exactly its subject, and records it") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), enrollments, config, AuditEvents.noop)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          _ <- clients.getClientsForSync.succeedsWith(Vector(record))
          generated <- CertificateSubject.generate(subject)
          chain <- service.sign(EdgeId("edge-1"), clientId, generated.csrPem)
          material <- ZIO.fromEither(PrivateClientCertificate(chain + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
        yield assertTrue(
          material.subjectValues("san_dns") == Set(dnsName),
          enrollments.issued.map(entry => (entry._1, entry._3)) == List((clientId, EdgeId("edge-1"))),
        )
      },
      test("the certificate an edge made for an enrolled client is recorded as cert.renewed by that edge") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val audit = RecordedAudit.unsafe
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), enrollments, config, audit)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          _ <- clients.getClientsForSync.succeedsWith(Vector(record))
          generated <- CertificateSubject.generate(subject)
          _ <- service.sign(EdgeId("edge-1"), clientId, generated.csrPem)
          recorded <- audit.drafts
        yield assertTrue(
          recorded.map(_.kind) == Vector(CentralAudit.CertRenewed),
          recorded.head.actor == Some((CentralAudit.Actors.Edge, Some("edge-1"))),
          recorded.head.target == Some(("client", clientId.toString)),
          detail(recorded.head, "operation") == Some("sign"),
          detail(recorded.head, "clientId") == Some(clientId.toString),
          detail(recorded.head, "serial") == enrollments.issued.headOption.map(_._2),
          detail(recorded.head, "notAfter").exists(Instant.parse(_).isAfter(Instant.now().plusSeconds(13 * 24 * 3600L))),
          detail(recorded.head, "subject") == Some(subject.principal.getName(X500Principal.RFC2253)),
          withoutPemMaterial(recorded.head, generated.privateKeyPem, generated.csrPem),
          onlyKnownMembers(recorded.head),
        )
      },
      // Even a CA that signs a name nobody asked for must not yield a certificate that passes for an edge.
      test("refuses a signed chain that carries a name identifying an edge") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val service =
          ClientCertificateService.Impl(FakeCa(Some("urn:versola:edge:edge-1")), clients, Issuances(), enrollments, config, AuditEvents.noop)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          _ <- clients.getClientsForSync.succeedsWith(Vector(record))
          generated <- CertificateSubject.generate(subject)
          exit <- service.sign(EdgeId("edge-1"), clientId, generated.csrPem).exit
        yield assertTrue(exit.isFailure, enrollments.issued.isEmpty)
      },
      test("refuses to store a certificate it issued that carries a name identifying an edge") {
        val clients = stub[OAuthClientService]
        val service =
          ClientCertificateService.Impl(FakeCa(Some("urn:versola:edge:edge-1")), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
        for exit <- service.register(createRequest).exit
        yield assertTrue(exit.isFailure, clients.registerClient.calls.isEmpty)
      },
      test("refuses a client the edge is not served, whatever the CSR says") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val fakeCa = FakeCa()
        val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), enrollments, config, AuditEvents.noop)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          // The sync filter, by tenant, does not list the client for this edge.
          _ <- clients.getClientsForSync.succeedsWith(Vector.empty)
          generated <- CertificateSubject.generate(subject)
          exit <- service.sign(EdgeId("edge-of-another-tenant"), clientId, generated.csrPem).exit
        yield assertTrue(exit.isFailure, fakeCa.requests.isEmpty, clients.getClientsForSync.calls == List(Some(EdgeId("edge-of-another-tenant"))))
      },
      test("is refused for a web client, whose tokens are bound to a certificate replicas would not share") {
        val clients = stub[OAuthClientService]
        val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), Enrollments(), config, AuditEvents.noop)
        for exit <- service.register(createRequest.copy(
            issueEdgeClientCertificate = false,
            enrollEdgeClientCertificate = true,
            applicationType = Some(ApplicationType.web),
          )).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("issueEdgeClientCertificate"), clients.registerClient.calls.isEmpty)
      },
      test("refuses a client that is not enrolled") {
        val clients = stub[OAuthClientService]
        val fakeCa = FakeCa()
        val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), Enrollments(), config, AuditEvents.noop)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          generated <- CertificateSubject.generate(subject)
          exit <- service.sign(EdgeId("edge-1"), clientId, generated.csrPem).exit
        yield assertTrue(exit.isFailure, fakeCa.requests.isEmpty)
      },
      test("refuses a request for another subject, or one that adds a name") {
        val clients = stub[OAuthClientService]
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val fakeCa = FakeCa()
        val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), enrollments, config, AuditEvents.noop)
        val subject = ClientCertificateRequests.subjectFor(clientId, auth).toOption.get
        for
          _ <- clients.getClientsForSync.succeedsWith(Vector(record))
          other <- CertificateSubject.generate(subject.copy(distinguishedName = "CN=someone-else"))
          wider <- CertificateSubject.generate(subject.copy(dnsNames = List(dnsName, "extra.test")))
          otherExit <- service.sign(EdgeId("edge-1"), clientId, other.csrPem).exit
          widerExit <- service.sign(EdgeId("edge-1"), clientId, wider.csrPem).exit
        yield assertTrue(otherExit.isFailure, widerExit.isFailure, fakeCa.requests.isEmpty, enrollments.issued.isEmpty)
      },
      test("tells an edge what the certificates of the enrolled clients among those it syncs must say") {
        val enrollments = Enrollments()
        enrollments.enrolled = Set(clientId)
        val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], Issuances(), enrollments, config, AuditEvents.noop)
        for subjects <- service.enrollmentSubjects(Vector(record, record.copy(id = ClientId("other"))))
        yield assertTrue(subjects.keySet == Set(clientId), subjects(clientId).dnsNames == List(dnsName))
      },
    ),
    test("renew reports a client central does not manage") {
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], Issuances(), Enrollments(), config, AuditEvents.noop)
      for renewed <- service.renew(clientId)
      yield assertTrue(!renewed)
    },
    test("an operator-supplied certificate takes the client out of renewal") {
      val issuances = Issuances()
      issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", Instant.now(), Instant.now()))
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], issuances, Enrollments(), config, AuditEvents.noop)
      val supplied = PrivateClientCertificate(TestCertificates.generate(dnsName = Some(dnsName)).bundle)
      for
        _ <- service.forgetIfReplaced(untouchedUpdate)
        keptWhenUntouched = issuances.rows.contains(clientId)
        _ <- service.forgetIfReplaced(untouchedUpdate.copy(edgeClientCertificate = Some(Patch.Modified(supplied))))
      yield assertTrue(keptWhenUntouched, !issuances.rows.contains(clientId))
    },
  )
