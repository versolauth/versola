package versola.central.configuration.clients.certificates

import org.bouncycastle.asn1.x509.{Extension, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.{CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, UpdateClientRequest}
import versola.central.configuration.clients.*
import versola.central.configuration.tenants.TenantId
import versola.util.{Patch, PrivateClientCertificate, TestCertificates}
import zio.*
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.time.Instant
import java.util.Date

object ClientCertificateServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("mobile-app")
  private val registeredAt = Instant.parse("2026-02-01T09:00:00Z")
  private val dnsName = "mobile-app.clients.versola.test"
  private val auth = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, dnsName)

  private val ca = TestCertificates.generate(subject = "CN=test-client-ca", ca = true)

  /** A CA that signs whatever request it is handed, as the real ones do for an authorised one:
    * the subject and SANs come from the request, the lifetime from `validity`. */
  private class FakeCa extends ClientCertificateIssuer:
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
        Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName))).foreach: san =>
          builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames.getInstance(san.getParsedValue))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ClientCertificateRequests.pem("CERTIFICATE", certificate.getEncoded)

  private class Issuances extends ClientCertificateIssuanceRepository:
    var rows: Map[ClientId, ClientCertificateIssuance] = Map.empty
    def upsert(issuance: ClientCertificateIssuance) = ZIO.succeed { rows += issuance.clientId -> issuance }
    def find(clientId: ClientId) = ZIO.succeed(rows.get(clientId))
    def delete(clientId: ClientId) = ZIO.succeed { rows -= clientId }
    def expiringBefore(deadline: Instant) =
      ZIO.succeed(rows.values.filter(_.notAfter.isBefore(deadline)).toVector.sortBy(_.notAfter))

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
    registrationFlow = None,
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
      registrationFlow = None,
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
    )

  private val config =
    TestCentralConfig.config.copy(clientCertificates = Some(
      CentralConfig.ClientCertificatesConfig(validity = 14.days, renewBefore = 4.days),
    ))

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
    registrationFlow = None,
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
  )

  def spec = suite("ClientCertificateService")(
    test("registers a client with a certificate the CA issued for its mtlsAuth") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val fakeCa = FakeCa()
      val service = ClientCertificateService.Impl(fakeCa, clients, issuances, config)
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
    test("passes a registration that asks for no certificate straight through, issuing nothing") {
      val clients = stub[OAuthClientService]
      val fakeCa = FakeCa()
      val service = ClientCertificateService.Impl(fakeCa, clients, Issuances(), config)
      val plain = createRequest.copy(issueEdgeClientCertificate = false, authMethod = AuthMethod.none, mtlsAuth = None)
      for
        _ <- clients.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
        _ <- service.register(plain)
      yield assertTrue(fakeCa.requests.isEmpty, clients.registerClient.calls.head._1 == plain)
    },
    test("refuses to issue when the request also supplies a certificate") {
      val clients = stub[OAuthClientService]
      val supplied = PrivateClientCertificate(TestCertificates.generate(dnsName = Some(dnsName)).bundle)
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), config)
      for exit <- service.register(createRequest.copy(edgeClientCertificate = Some(supplied))).exit
      yield assertTrue(exit.isFailure, clients.registerClient.calls.isEmpty)
    },
    test("registers by CN=<client>,OU=<tenant>,O=Versola when the request names no mtlsAuth") {
      val clients = stub[OAuthClientService]
      val service = ClientCertificateService.Impl(FakeCa(), clients, Issuances(), config)
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
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], Issuances(), config)
      for exit <- service.register(createRequest.copy(authMethod = AuthMethod.client_secret)).exit
      yield assertTrue(exit.isFailure)
    },
    test("says so when central has no CA to issue from") {
      val service = ClientCertificateService.Impl(ClientCertificateIssuer.notConfigured, stub[OAuthClientService], Issuances(), config)
      for exit <- service.register(createRequest).exit
      yield assertTrue(exit.isFailure, exit.toString.contains("none configured"))
    },
    test("renews only the certificates inside the renewal window") {
      val clients = stub[OAuthClientService]
      val issuances = Issuances()
      val other = ClientId("other-app")
      val service = ClientCertificateService.Impl(FakeCa(), clients, issuances, config)
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
      val service = ClientCertificateService.Impl(failing, clients, issuances, config)
      for
        now <- Clock.instant
        _ = issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", now.plusSeconds(60), now))
        _ <- clients.getAllClients.succeedsWith(Vector(record))
        renewed <- service.renewDue
      yield assertTrue(renewed == 0, clients.updateClient.calls.isEmpty, issuances.rows(clientId).serial == "01")
    },
    test("renew reports a client central does not manage") {
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], Issuances(), config)
      for renewed <- service.renew(clientId)
      yield assertTrue(!renewed)
    },
    test("an operator-supplied certificate takes the client out of renewal") {
      val issuances = Issuances()
      issuances.rows = Map(clientId -> ClientCertificateIssuance(clientId, "01", Instant.now(), Instant.now()))
      val service = ClientCertificateService.Impl(FakeCa(), stub[OAuthClientService], issuances, config)
      val supplied = PrivateClientCertificate(TestCertificates.generate(dnsName = Some(dnsName)).bundle)
      for
        _ <- service.forgetIfReplaced(untouchedUpdate)
        keptWhenUntouched = issuances.rows.contains(clientId)
        _ <- service.forgetIfReplaced(untouchedUpdate.copy(edgeClientCertificate = Some(Patch.Modified(supplied))))
      yield assertTrue(keptWhenUntouched, !issuances.rows.contains(clientId))
    },
  )
