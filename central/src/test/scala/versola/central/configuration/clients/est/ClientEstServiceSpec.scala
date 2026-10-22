package versola.central.configuration.clients.est

import org.bouncycastle.asn1.x509.{GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.{JcaPKCS10CertificationRequest, JcaPKCS10CertificationRequestBuilder}
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.CreateClientRequest
import versola.central.configuration.clients.*
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer, ClientCertificateRequests}
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.{CertificateSubject, SecureRandom, TestCertificates}
import zio.*
import zio.test.*

import java.io.{ByteArrayInputStream, StringReader}
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator}
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

object ClientEstServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("bank-a")
  private val otherClientId = ClientId("bank-b")
  private val registeredAt = Instant.parse("2026-02-01T09:00:00Z")
  private val defaultSubject = "CN=bank-a,OU=tenant-a,O=Versola"

  private val ca = TestCertificates.generate(subject = "CN=test-client-ca", ca = true)

  /** A CA that signs the request as it is, the way the real ones do for an authorised request. */
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
        addedUri.foreach(uri =>
          builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, uri)),
          ),
        )
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)))
        CertificateSubject.pem("CERTIFICATE", certificate.getEncoded)

  private class InMemoryEst extends ClientEstRepository:
    var enabled: Map[ClientId, Instant] = Map.empty
    var generations: Map[ClientId, Int] = Map.empty
    var tokens: Map[String, (ClientId, Instant, Boolean)] = Map.empty
    var chains: Map[ClientId, Instant] = Map.empty
    var certificates: Map[String, EstCertificate] = Map.empty

    private def generationOf(clientId: ClientId) = generations.getOrElse(clientId, 0)

    def enable(clientId: ClientId, at: Instant) = ZIO.succeed { enabled += clientId -> at }
    def isEnabled(clientId: ClientId) = ZIO.succeed(enabled.contains(clientId))
    def generation(clientId: ClientId) = ZIO.succeed(Option.when(enabled.contains(clientId))(generationOf(clientId)))
    def all =
      ZIO.succeed:
        enabled.toVector.map: (id, at) =>
          EstEnrollmentRow(
            id,
            at,
            chains.get(id),
            certificates.values.filter(c => c.clientId == id && c.generation == generationOf(id)).map(_.notAfter).maxOption,
          )
    def createToken(tokenHash: String, clientId: ClientId, expiresAt: Instant) =
      ZIO.succeed:
        tokens = tokens.filterNot((_, v) => v._1 == clientId && !v._3) + (tokenHash -> (clientId, expiresAt, false))
    def peekToken(tokenHash: String, now: Instant) =
      ZIO.succeed(tokens.get(tokenHash).collect { case (id, expiresAt, false) if expiresAt.isAfter(now) => id })
    def consumeToken(tokenHash: String, now: Instant) =
      ZIO.succeed:
        tokens.get(tokenHash) match
          case Some((id, expiresAt, false)) if expiresAt.isAfter(now) =>
            tokens += tokenHash -> (id, expiresAt, true)
            Some(id)
          case _ => None
    def releaseToken(tokenHash: String) =
      ZIO.succeed { tokens = tokens.updatedWith(tokenHash)(_.map((id, expiresAt, _) => (id, expiresAt, false))) }
    def revoke(clientId: ClientId) =
      ZIO.succeed:
        generations += clientId -> (generationOf(clientId) + 1)
        chains -= clientId
        tokens = tokens.filterNot((_, v) => v._1 == clientId && !v._3)
    def recordEnrollment(certificate: NewEstCertificate, now: Instant) =
      ZIO.succeed:
        val generation = generationOf(certificate.clientId) + 1
        generations += certificate.clientId -> generation
        chains += certificate.clientId -> now
        add(certificate, generation)
    def recordRenewal(
        presentedThumbprint: String,
        certificate: NewEstCertificate,
        generation: Int,
        now: Instant,
        maxAge: Duration,
        autoExtend: Boolean,
    ) =
      ZIO.succeed:
        if generationOf(certificate.clientId) != generation then RenewalRecorded.GenerationChanged
        else
          val started = chains.getOrElse(certificate.clientId, now)
          val ended = started.plus(maxAge).isBefore(now)
          if ended && !autoExtend then RenewalRecorded.ChainEnded
          else
            val start = if ended then now else started
            chains += certificate.clientId -> start
            certificates =
              certificates.updatedWith(presentedThumbprint)(_.map(c => if c.supersededAt.isEmpty then c.copy(supersededAt = Some(now)) else c))
            add(certificate, generation)
            RenewalRecorded.Recorded(start)
    def extendChain(clientId: ClientId, at: Instant) = ZIO.succeed { chains += clientId -> at }
    def chainStartedAt(clientId: ClientId) = ZIO.succeed(chains.get(clientId))
    def findCertificate(thumbprint: String) = ZIO.succeed(certificates.get(thumbprint))

    private def add(certificate: NewEstCertificate, generation: Int): Unit =
      certificates += certificate.thumbprint -> EstCertificate(
        certificate.clientId,
        certificate.thumbprint,
        certificate.serial,
        certificate.notAfter,
        certificate.issuedAt,
        generation,
        None,
      )

  private def record(id: ClientId = clientId, mtlsAuth: Option[MutualTlsAuth] = None, authMethod: AuthMethod = AuthMethod.tls_client_auth) =
    OAuthClientRecord(
      id = id,
      tenantId = tenantId,
      clientName = Map("en" -> "Bank"),
      redirectUris = Set.empty,
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
      dpopBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = authMethod,
      mtlsAuth = mtlsAuth,
      certificateBoundAccessTokens = true,
      jwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = None,
      createdAt = registeredAt,
      applicationType = ApplicationType.web,
      themeOverrides = None,
    )

  /** Short chains and certificates that outlive them, so that a chain can end while the certificate in hand is still good. */
  private def config(autoExtend: Boolean = false, renewWindow: Double = 1.0) =
    TestCentralConfig.config.copy(
      clientCertificates = Some(CentralConfig.ClientCertificatesConfig(validity = 14.days)),
      clientIdentity = CentralConfig.ClientIdentityConfig(maxAge = 10.days, autoExtend = autoExtend, renewWindow = renewWindow),
    )

  private class Env(
      clients: List[OAuthClientRecord] = List(record()),
      enabled: List[ClientId] = List(clientId),
      issuer: ClientCertificateIssuer = FakeCa(),
      autoExtend: Boolean = false,
      renewWindow: Double = 1.0,
  ):
    val repository = InMemoryEst()
    repository.enabled = enabled.map(_ -> registeredAt).toMap
    val clientService = stub[OAuthClientService]
    val service: URIO[SecureRandom, ClientEstService] =
      for
        _ <- clientService.findClient.returnsZIO(id => ZIO.succeed(clients.find(_.id == id)))
        _ <- clientService.getAllClients.succeedsWith(clients.toVector)
        random <- ZIO.service[SecureRandom]
      yield ClientEstService.Impl(repository, clientService, issuer, random, config(autoExtend, renewWindow))

  private def keyPair(algorithm: String = "EC", size: Int = 2048): KeyPair =
    val generator = KeyPairGenerator.getInstance(algorithm)
    if algorithm == "EC" then generator.initialize(ECGenParameterSpec("secp256r1")) else generator.initialize(size)
    generator.generateKeyPair()

  /** What a bank's EST client sends: a request for `subject` signed by `keys`. */
  private def csr(subject: String = defaultSubject, keys: KeyPair = keyPair()): String =
    val signature = if keys.getPublic.getAlgorithm == "EC" then "SHA256withECDSA" else "SHA256withRSA"
    val request = JcaPKCS10CertificationRequestBuilder(X500Principal(subject), keys.getPublic)
      .build(JcaContentSignerBuilder(signature).build(keys.getPrivate))
    CertificateSubject.pem("CERTIFICATE REQUEST", request.getEncoded)

  private def leafOf(chain: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
      .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
      .iterator.next.asInstanceOf[X509Certificate]

  /** The specs' certificates are valid in real time, so the clock the service reads is set to it. */
  private val realTime: UIO[Unit] = Clock.ClockLive.instant.flatMap(now => TestClock.setTime(now))

  private def enrol(env: Env, service: ClientEstService, request: String = csr()) =
    for
      issued <- service.issueEnrollmentToken(clientId)
      identity <- service.enroll(issued.token, Some(clientId), request)
    yield identity

  def spec = suite("ClientEstService")(
    suite("issueEnrollmentToken")(
      test("keeps only the hash of the token, and tells the operator the subject to ask for") {
        val env = Env()
        for
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
        yield assertTrue(
          env.repository.tokens.size == 1,
          !env.repository.tokens.contains(issued.token),
          issued.subject == defaultSubject,
        )
      },
      test("refuses a client that is not registered with EST, and a validity beyond the maximum") {
        val env = Env(enabled = Nil)
        for
          service <- env.service
          notEst <- service.issueEnrollmentToken(clientId).flip
          enabledEnv = Env()
          other <- enabledEnv.service
          tooLong <- other.issueEnrollmentToken(clientId, ClientEstService.MaxTokenValidity + 1.hour).flip
        yield assertTrue(
          notEst == ClientEstService.Refused(ClientEstService.Refusal.Forbidden, notEst.asInstanceOf[ClientEstService.Refused].reason),
          tooLong.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.BadRequest,
        )
      },
    ),
    suite("enroll")(
      test("trades the token for a certificate with the registered subject, and begins the chain") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          identity <- enrol(env, service)
          leaf = leafOf(identity.chain)
          states <- service.states
        yield assertTrue(
          leaf.getSubjectX500Principal == X500Principal(defaultSubject),
          env.repository.certificates.size == 1,
          env.repository.chains.contains(clientId),
          identity.chainEndsAt.isDefined,
          !states(clientId).pending,
        )
      },
      test("a token is good once") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          _ <- service.enroll(issued.token, None, csr())
          again <- service.enroll(issued.token, None, csr()).flip
        yield assertTrue(again == ClientEstService.InvalidCredentials)
      },
      test("a token of another client, or from the wrong user name, does not enrol, whatever the request says") {
        val env = Env(clients = List(record(), record(otherClientId)), enabled = List(clientId, otherClientId))
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(otherClientId)
          wrongUser <- service.enroll(issued.token, Some(clientId), csr()).flip
          garbage <- service.enroll("not-a-token", None, "garbage").flip
        yield assertTrue(wrongUser == ClientEstService.InvalidCredentials, garbage == ClientEstService.InvalidCredentials)
      },
      test("an expired token is refused like an unknown one") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          _ <- TestClock.adjust(ClientEstService.TokenValidity + 1.second)
          expired <- service.enroll(issued.token, None, csr()).flip
        yield assertTrue(expired == ClientEstService.InvalidCredentials)
      },
      test("a request for another subject is refused before the token is spent, and the subject is not the request's") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          wrong <- service.enroll(issued.token, None, csr(subject = "CN=bank-b,OU=tenant-a,O=Versola")).flip
          right <- service.enroll(issued.token, None, csr())
        yield assertTrue(
          wrong.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.BadRequest,
          wrong.asInstanceOf[ClientEstService.Refused].reason.contains(defaultSubject),
          leafOf(right.chain).getSubjectX500Principal == X500Principal(defaultSubject),
        )
      },
      test("a weak RSA key and a curve other than P-256 are refused") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          weak <- service.enroll(issued.token, None, csr(keys = keyPair("RSA", 1024))).flip
          p384 = {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp384r1"))
            generator.generateKeyPair()
          }
          curve <- service.enroll(issued.token, None, csr(keys = p384)).flip
          rsa <- service.enroll(issued.token, None, csr(keys = keyPair("RSA", 2048)))
        yield assertTrue(
          weak.asInstanceOf[ClientEstService.Refused].reason.contains("2048"),
          curve.asInstanceOf[ClientEstService.Refused].reason.contains("P-256"),
          rsa.chain.contains("BEGIN CERTIFICATE"),
        )
      },
      test("a new token replaces the client's earlier ones that were not used") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- service.issueEnrollmentToken(clientId)
          second <- service.issueEnrollmentToken(clientId)
          old <- service.enroll(first.token, None, csr()).flip
          good <- service.enroll(second.token, None, csr())
        yield assertTrue(old == ClientEstService.InvalidCredentials, good.chain.contains("BEGIN CERTIFICATE"))
      },
      test("a token stays spent when the request is interrupted after the CA signed: never a second certificate for one token") {
        class Hanging extends ClientCertificateIssuer:
          val signed = Unsafe.unsafe(Promise.unsafe.make[Nothing, Unit](FiberId.None)(using _))
          override def sign(request: CertificateSigningRequest): Task[String] =
            FakeCa().sign(request) *> signed.succeed(()) *> ZIO.never
        val hanging = Hanging()
        val env = Env(issuer = hanging)
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          fiber <- service.enroll(issued.token, None, csr()).fork
          _ <- hanging.signed.await
          _ <- fiber.interrupt
          again <- service.enroll(issued.token, None, csr()).flip
        yield assertTrue(again == ClientEstService.InvalidCredentials, env.repository.certificates.isEmpty)
      },
      test("a token spent for an enrolment the CA could not serve is given back, and works once the CA does") {
        class Flaky extends ClientCertificateIssuer:
          var down = true
          override def sign(request: CertificateSigningRequest): Task[String] =
            if down then ZIO.fail(RuntimeException("the CA is unreachable")) else FakeCa().sign(request)
        val failing = Flaky()
        val env = Env(issuer = failing)
        for
          _ <- realTime
          service <- env.service
          issued <- service.issueEnrollmentToken(clientId)
          first <- service.enroll(issued.token, None, csr()).flip
          _ = failing.down = false
          second <- service.enroll(issued.token, None, csr())
        yield assertTrue(first.getMessage.contains("unreachable"), second.chain.contains("BEGIN CERTIFICATE"))
      },
      test("a certificate the CA returns with a name more than the registration is not handed over") {
        val env = Env(issuer = FakeCa(addedUri = Some("urn:versola:edge:somebody")))
        for
          _ <- realTime
          service <- env.service
          failure <- enrol(env, service).flip
        yield assertTrue(failure.isInstanceOf[ClientEstService.Refused], env.repository.certificates.isEmpty)
      },
    ),
    suite("renew")(
      test("a new certificate for the one in hand, with the same subject, and the old one is not revoked") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          renewed <- service.renew(first.chain, csr())
        yield assertTrue(
          leafOf(renewed.chain).getSubjectX500Principal == leafOf(first.chain).getSubjectX500Principal,
          leafOf(renewed.chain).getSerialNumber != leafOf(first.chain).getSerialNumber,
          env.repository.certificates.size == 2,
          renewed.chainEndsAt == first.chainEndsAt,
        )
      },
      test("the certificate a renewal replaced may ask again within the grace, for a client that did not get the answer") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          _ <- service.renew(first.chain, csr())
          _ <- TestClock.adjust(1.minute)
          again <- service.renew(first.chain, csr())
        yield assertTrue(again.chain.contains("BEGIN CERTIFICATE"))
      },
      test("a certificate that was replaced and renews again after the grace closes the chain, for both holders of the key") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          second <- service.renew(first.chain, csr())
          _ <- TestClock.adjust(ClientEstService.ForkGrace + 1.minute)
          fork <- service.renew(first.chain, csr()).flip
          afterwards <- service.renew(second.chain, csr()).flip
        yield assertTrue(
          fork.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.ChainClosed,
          afterwards == ClientEstService.InvalidCredentials,
        )
      },
      test("a re-enrolment ends the right of every earlier certificate to renew, and the new one renews") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          second <- service.renew(first.chain, csr())
          third <- enrol(env, service)
          withFirst <- service.renew(first.chain, csr()).flip
          withSecond <- service.renew(second.chain, csr()).flip
          withThird <- service.renew(third.chain, csr())
        yield assertTrue(
          withFirst == ClientEstService.InvalidCredentials,
          withSecond == ClientEstService.InvalidCredentials,
          withThird.chain.contains("BEGIN CERTIFICATE"),
        )
      },
      test("a revocation ends the right to renew, withdraws the open token, and the client is pending until it enrols again") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          open <- service.issueEnrollmentToken(clientId)
          _ <- service.revoke(clientId)
          renewed <- service.renew(first.chain, csr()).flip
          withdrawn <- service.enroll(open.token, None, csr()).flip
          states <- service.states
          again <- enrol(env, service)
          renewedAgain <- service.renew(again.chain, csr())
        yield assertTrue(
          renewed == ClientEstService.InvalidCredentials,
          withdrawn == ClientEstService.InvalidCredentials,
          states(clientId).pending,
          renewedAgain.chain.contains("BEGIN CERTIFICATE"),
        )
      },
      test("a revocation while the CA is answering means the certificate it signed is not recorded, and the renewal is refused") {
        class Slow extends ClientCertificateIssuer:
          val signing = Unsafe.unsafe(Promise.unsafe.make[Nothing, Unit](FiberId.None)(using _))
          val proceed = Unsafe.unsafe(Promise.unsafe.make[Nothing, Unit](FiberId.None)(using _))
          var holding = false
          override def sign(request: CertificateSigningRequest): Task[String] =
            if holding then signing.succeed(()) *> proceed.await *> FakeCa().sign(request) else FakeCa().sign(request)
        val slow = Slow()
        val env = Env(issuer = slow)
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          _ = slow.holding = true
          renewing <- service.renew(first.chain, csr()).flip.fork
          _ <- slow.signing.await
          _ <- service.revoke(clientId)
          _ <- slow.proceed.succeed(())
          refused <- renewing.join
        yield assertTrue(refused == ClientEstService.InvalidCredentials, env.repository.certificates.size == 1)
      },
      test("a certificate that is not near its end is not renewed: the last part of its life only") {
        val env = Env(renewWindow = 1.0 / 3)
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          early <- service.renew(first.chain, csr()).flip
          _ <- TestClock.adjust(10.days)
          opened <- service.renew(first.chain, csr())
        yield assertTrue(
          early.asInstanceOf[ClientEstService.Refused].reason.contains("too early"),
          opened.chain.contains("BEGIN CERTIFICATE"),
        )
      },
      test("a request that reuses the key of the certificate it replaces is refused") {
        val env = Env()
        val keys = keyPair()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service, csr(keys = keys))
          reused <- service.renew(first.chain, csr(keys = keys)).flip
        yield assertTrue(reused.asInstanceOf[ClientEstService.Refused].reason.contains("new key"))
      },
      test("a request for another subject is refused") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          wrong <- service.renew(first.chain, csr(subject = "CN=bank-b,OU=tenant-a,O=Versola")).flip
        yield assertTrue(wrong.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.BadRequest)
      },
      test("an expired certificate does not renew: an operator enrols the client again") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          _ <- TestClock.adjust(15.days)
          expired <- service.renew(first.chain, csr()).flip
        yield assertTrue(expired == ClientEstService.InvalidCredentials)
      },
      test("a certificate that was not issued through EST is not the start of a chain") {
        val env = Env()
        val foreign = TestCertificates.generate(subject = defaultSubject)
        for
          _ <- realTime
          service <- env.service
          failure <- service.renew(foreign.certificatePem, csr()).flip
        yield assertTrue(failure == ClientEstService.InvalidCredentials)
      },
      test("a certificate that names an edge is not the start of a chain") {
        val env = Env()
        val edgeCertificate = TestCertificates.generate(subject = defaultSubject, uris = List("urn:versola:edge:x"))
        for
          _ <- realTime
          service <- env.service
          failure <- service.renew(edgeCertificate.certificatePem, csr()).flip
        yield assertTrue(failure == ClientEstService.InvalidCredentials)
      },
      test("a client that is no longer registered with EST is no longer anyone's: its certificates do not renew") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          _ = env.repository.enabled = Map.empty
          refused <- service.renew(first.chain, csr()).flip
        yield assertTrue(refused == ClientEstService.InvalidCredentials)
      },
      test("a certificate whose subject is no longer the registered one does not renew") {
        val enrolling = Env()
        for
          _ <- realTime
          first <- enrolling.service.flatMap(service => enrol(enrolling, service))
          // The operator has since registered the client by another subject.
          changed = record(mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=bank-a-renamed")))
          env = Env(clients = List(changed))
          _ = env.repository.certificates = enrolling.repository.certificates
          _ = env.repository.generations = enrolling.repository.generations
          service <- env.service
          refused <- service.renew(first.chain, csr(subject = "CN=bank-a-renamed")).flip
        yield assertTrue(refused.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.Forbidden)
      },
    ),
    suite("the chain")(
      test("ends at max-age: renewing is refused until an operator extends it, and then works") {
        val env = Env()
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          // 11 days: past the chain's 10, inside the certificate's 14.
          _ <- TestClock.adjust(11.days)
          refused <- service.renew(first.chain, csr()).flip
          _ <- service.extend(clientId)
          renewed <- service.renew(first.chain, csr())
        yield assertTrue(
          refused.asInstanceOf[ClientEstService.Refused].kind == ClientEstService.Refusal.Forbidden,
          refused.asInstanceOf[ClientEstService.Refused].reason.contains("extend"),
          renewed.chainEndsAt.exists(_.isAfter(first.chainEndsAt.get)),
        )
      },
      test("is extended by central itself with auto-extend") {
        val env = Env(autoExtend = true)
        for
          _ <- realTime
          service <- env.service
          first <- enrol(env, service)
          _ <- TestClock.adjust(11.days)
          renewed <- service.renew(first.chain, csr())
        yield assertTrue(first.chainEndsAt.isEmpty, renewed.chain.contains("BEGIN CERTIFICATE"))
      },
      test("cannot be extended for a client that is not registered with EST") {
        val env = Env(enabled = Nil)
        for
          service <- env.service
          failure <- service.extend(clientId).flip
        yield assertTrue(failure.isInstanceOf[ClientEstService.Refused])
      },
    ),
    suite("registerClient")(
      test("registers the client by the default subject and records it") {
        val env = Env(enabled = Nil)
        for
          service <- env.service
          _ <- env.clientService.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None))
          _ <- service.registerClient(request())
          sent = env.clientService.registerClient.calls.head._1
        yield assertTrue(
          env.repository.enabled.contains(clientId),
          sent.mtlsAuth.contains(ClientCertificateRequests.defaultAuth(tenantId, clientId)),
        )
      },
      test("refuses a subject in the namespace that names an edge") {
        val env = Env(enabled = Nil)
        for
          service <- env.service
          refused <-
            service.registerClient(request(mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, "urn:versola:edge:x")))).flip
        yield assertTrue(refused.isInstanceOf[InvalidRegistrationConfiguration], env.clientService.registerClient.calls.isEmpty)
      },
      test("refuses a subject another client is registered by already: it would be that client at /token") {
        val taken = record(otherClientId, mtlsAuth = Some(ClientCertificateRequests.defaultAuth(tenantId, clientId)))
        val env = Env(clients = List(taken), enabled = Nil)
        for
          service <- env.service
          refused <- service.registerClient(request()).flip
        yield assertTrue(
          refused.asInstanceOf[InvalidRegistrationConfiguration].reason.contains(otherClientId),
          env.clientService.registerClient.calls.isEmpty,
          env.repository.enabled.isEmpty,
        )
      },
      test("refuses a client that does not authenticate with a certificate, a native app, and a supplied certificate") {
        val env = Env(enabled = Nil)
        for
          service <- env.service
          secret <- service.registerClient(request(authMethod = AuthMethod.client_secret)).flip
          native <- service.registerClient(request(applicationType = Some(ApplicationType.native))).flip
          edge <- service.registerClient(request(enrollEdge = true)).flip
        yield assertTrue(
          secret.isInstanceOf[InvalidRegistrationConfiguration],
          native.isInstanceOf[InvalidRegistrationConfiguration],
          edge.isInstanceOf[InvalidRegistrationConfiguration],
          env.clientService.registerClient.calls.isEmpty,
        )
      },
    ),
  ).provide(SecureRandom.live) @@ TestAspect.sequential

  private def request(
      authMethod: AuthMethod = AuthMethod.tls_client_auth,
      applicationType: Option[ApplicationType] = Some(ApplicationType.web),
      enrollEdge: Boolean = false,
      mtlsAuth: Option[MutualTlsAuth] = None,
  ) =
    CreateClientRequest(
      tenantId = tenantId,
      id = clientId,
      clientName = Map("en" -> "Bank"),
      redirectUris = Set.empty,
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
      dpopBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = authMethod,
      mtlsAuth = mtlsAuth,
      certificateBoundAccessTokens = true,
      jwks = None,
      generateJwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = None,
      applicationType = applicationType,
      issueEdgeClientCertificate = false,
      enrollEdgeClientCertificate = enrollEdge,
      themeOverrides = None,
      estEnrollment = true,
    )
