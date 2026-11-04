package versola.oauth.est

import org.scalamock.stubs.ZIOStubs
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.util.audit.AuditOutcome
import versola.util.{CoreConfig, TestCertificates}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.Date

object EstServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val issuedChain = TestCertificates.generate(subject = "CN=bank-a,OU=tenant-a,O=Versola").certificatePem
  private val endsAt = Instant.parse("2027-10-22T00:00:00Z")
  private val credentials = EstService.Credentials(Some("bank-a"), "TOKEN")
  private val csrBody = java.util.Base64.getEncoder.encodeToString(
    {
      val generator = java.security.KeyPairGenerator.getInstance("EC")
      generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
      val keys = generator.generateKeyPair()
      org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(javax.security.auth.x500.X500Principal("CN=bank-a"), keys.getPublic)
        .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate)).getEncoded
    },
  )

  private val ca: Option[Array[Byte]] = Some(EstCodec.certsOnly(EstCodec.certificates(issuedChain).get))

  private def limiter(limit: Int) =
    AttemptLimiter(limit, 1.minute, Unsafe.unsafe(Ref.unsafe.make(Map.empty[String, (Instant, Int)])(using _)))

  private val audit = CapturingAuditEvents.shared()

  private def service(central: EstCentralClient, enabled: Boolean = true, attempts: Int = 1000) =
    EstService.Impl(Option.when(enabled)(ca.get), central, limiter(attempts), audit)

  private def csrWith(algorithm: String, init: java.security.KeyPairGenerator => Unit): String =
    val generator = java.security.KeyPairGenerator.getInstance(algorithm)
    init(generator)
    val keys = generator.generateKeyPair()
    val signature = if algorithm == "EC" then "SHA256withECDSA" else "SHA256withRSA"
    java.util.Base64.getEncoder.encodeToString(
      org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(javax.security.auth.x500.X500Principal("CN=bank-a"), keys.getPublic)
        .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(signature).build(keys.getPrivate)).getEncoded,
    )

  /** Valid in real time, so the service's clock is set to it. */
  private val realTime: UIO[Unit] = Clock.ClockLive.instant.flatMap(now => TestClock.setTime(now))

  /** An EST whose audit log is its own, so that cases running side by side do not read each other's events. */
  private def audited(central: EstCentralClient, attempts: Int = 1000) =
    val recording = CapturingAuditEvents.shared()
    (recording, EstService.Impl(Option.when(true)(ca.get), central, limiter(attempts), recording))

  private val leaf = EstCodec.certificates(issuedChain).get.head

  def spec = suite("EstService")(
    suite("enroll")(
      test("passes the token, the user name and the request to central, and answers with a certs-only PKCS#7") {
        val central = stub[EstCentralClient]
        for
          _ <- central.enroll.succeedsWith(EstCentralClient.Issued(issuedChain, Some(endsAt)))
          issued <- service(central).enroll(credentials, csrBody)
        yield assertTrue(
          issued.chainEndsAt.contains(endsAt),
          issued.pkcs7.nonEmpty,
          central.enroll.calls.map((token, clientId, csr) => (token, clientId, csr.startsWith("-----BEGIN CERTIFICATE REQUEST"))) ==
            List(("TOKEN", Some("bank-a"), true)),
        )
      },
      test("a request that is not a PKCS#10 is refused without asking central") {
        val central = stub[EstCentralClient]
        for failure <- service(central).enroll(credentials, "garbage").flip
        yield assertTrue(failure.isInstanceOf[EstError.BadRequest], central.enroll.calls.isEmpty)
      },
      test("what central refuses is passed on as it is: the token, the request, the client") {
        val central = stub[EstCentralClient]
        for
          _ <- central.enroll.failsWith(EstError.Unauthorized)
          unauthorized <- service(central).enroll(credentials, csrBody).flip
          _ <- central.enroll.failsWith(EstError.BadRequest("wrong subject"))
          badRequest <- service(central).enroll(credentials, csrBody).flip
          _ <- central.enroll.failsWith(EstError.Forbidden("chain limit"))
          forbidden <- service(central).enroll(credentials, csrBody).flip
        yield assertTrue(
          unauthorized == EstError.Unauthorized,
          badRequest == EstError.BadRequest("wrong subject"),
          forbidden == EstError.Forbidden("chain limit"),
        )
      },
      test("a key central would refuse is refused here, without asking central") {
        val central = stub[EstCentralClient]
        val weak = csrWith("RSA", _.initialize(1024))
        val p384 = csrWith("EC", _.initialize(java.security.spec.ECGenParameterSpec("secp384r1")))
        for
          weakKey <- service(central).enroll(credentials, weak).flip
          otherCurve <- service(central).enroll(credentials, p384).flip
        yield assertTrue(weakKey.isInstanceOf[EstError.BadRequest], otherCurve.isInstanceOf[EstError.BadRequest], central.enroll.calls.isEmpty)
      },
      test("a request with challengePassword is 400 before central is asked") {
        val central = stub[EstCentralClient]
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val bound = java.util.Base64.getEncoder.encodeToString(
          org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(javax.security.auth.x500.X500Principal("CN=bank-a"), keys.getPublic)
            .addAttribute(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.pkcs_9_at_challengePassword, org.bouncycastle.asn1.DERPrintableString("x"))
            .build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate)).getEncoded,
        )
        for failure <- service(central).enroll(credentials, bound).flip
        yield assertTrue(failure.isInstanceOf[EstError.BadRequest], central.enroll.calls.isEmpty)
      },
      test("attempts at one client id are limited per window, and another client id has its own") {
        val central = stub[EstCentralClient]
        val limited = service(central, attempts = 2)
        for
          _ <- central.enroll.failsWith(EstError.Unauthorized)
          first <- limited.enroll(credentials, csrBody).flip
          second <- limited.enroll(credentials, csrBody).flip
          third <- limited.enroll(credentials, csrBody).flip
          other <- limited.enroll(credentials.copy(clientId = Some("bank-b")), csrBody).flip
          _ <- TestClock.adjust(61.seconds)
          later <- limited.enroll(credentials, csrBody).flip
        yield assertTrue(
          first == EstError.Unauthorized,
          second == EstError.Unauthorized,
          third == EstError.TooManyRequests,
          other == EstError.Unauthorized,
          later == EstError.Unauthorized,
          central.enroll.calls.size == 4,
        )
      },
      test("answers as if nothing were there when EST is not enabled") {
        val central = stub[EstCentralClient]
        for failure <- service(central, enabled = false).enroll(credentials, csrBody).flip
        yield assertTrue(failure == EstError.Disabled, central.enroll.calls.isEmpty)
      },
    ),
    suite("reenroll")(
      test("hands the presented certificate to central with the request") {
        val central = stub[EstCentralClient]
        val presented = TestCertificates.generate(subject = "CN=bank-a,OU=tenant-a,O=Versola")
        for
          _ <- realTime
          _ <- central.renew.succeedsWith(EstCentralClient.Issued(issuedChain, None))
          issued <- service(central).reenroll(presented.certificate, csrBody)
        yield assertTrue(
          issued.pkcs7.nonEmpty,
          issued.chainEndsAt.isEmpty,
          central.renew.calls.map(_._1) == List(presented.certificatePem.trim + "\n"),
        )
      },
      test("an expired certificate is not renewed with itself, and central is not asked") {
        val central = stub[EstCentralClient]
        val expired = TestCertificates.generate(
          subject = "CN=bank-a",
          validity = Some((Date(java.lang.System.currentTimeMillis() - 2 * 86_400_000L), Date(java.lang.System.currentTimeMillis() - 86_400_000L))),
        )
        for
          _ <- realTime
          failure <- service(central).reenroll(expired.certificate, csrBody).flip
        yield assertTrue(failure == EstError.Unauthorized, central.renew.calls.isEmpty)
      },
      test("a certificate that is not valid yet is refused too") {
        val central = stub[EstCentralClient]
        val early = TestCertificates.generate(
          subject = "CN=bank-a",
          validity = Some((Date(java.lang.System.currentTimeMillis() + 86_400_000L), Date(java.lang.System.currentTimeMillis() + 2 * 86_400_000L))),
        )
        for
          _ <- realTime
          failure <- service(central).reenroll(early.certificate, csrBody).flip
        yield assertTrue(failure == EstError.Unauthorized, central.renew.calls.isEmpty)
      },
      test("a key reused or a subject changed is central's refusal, and reaches the client as 400 would") {
        val central = stub[EstCentralClient]
        val presented = TestCertificates.generate(subject = "CN=bank-a")
        for
          _ <- realTime
          _ <- central.renew.failsWith(EstError.BadRequest("a renewal needs a new key"))
          failure <- service(central).reenroll(presented.certificate, csrBody).flip
        yield assertTrue(failure == EstError.BadRequest("a renewal needs a new key"))
      },
    ),
    suite("startup")(
      test("an enabled EST with no CA to hand out does not start: the CA is named, never inferred from the trust anchors") {
        val config = TestEnvConfig.coreConfig.copy(est = Some(CoreConfig.EstConfig(enabled = true)))
        for failure <-
            ZIO.scoped(EstService.live.build).provide(
              ZLayer.succeed(config),
              ZLayer.succeed(stub[EstCentralClient]: EstCentralClient),
              ZLayer.succeed[versola.util.audit.AuditEvents](audit),
            ).flip
        yield assertTrue(failure.getMessage.contains("est.ca-certificates"))
      },
      test("a disabled EST needs nothing") {
        val config = TestEnvConfig.coreConfig.copy(est = None)
        (for
          service <- ZIO.scoped(EstService.live.build.map(_.get))
          failure <- service.caCertificates.flip
        yield assertTrue(failure == EstError.Disabled)).provide(
          ZLayer.succeed(config),
          ZLayer.succeed(stub[EstCentralClient]: EstCentralClient),
          ZLayer.succeed[versola.util.audit.AuditEvents](audit),
        )
      },
    ),
    suite("caCertificates")(
      test("hands out the configured CA, and nothing when EST is off") {
        val central = stub[EstCentralClient]
        for
          on <- service(central).caCertificates
          off <- service(central, enabled = false).caCertificates.flip
        yield assertTrue(on.nonEmpty, off == EstError.Disabled)
      },
    ),
    suite("audit log (cert.*)")(
      test("an enrolment records cert.enrolled with the serial and expiry central's certificate has; the client is the actor") {
        val central = stub[EstCentralClient]
        val (recording, est) = audited(central)
        for
          _ <- central.enroll.succeedsWith(EstCentralClient.Issued(issuedChain, Some(endsAt)))
          _ <- est.enroll(credentials, csrBody)
          drafts <- recording.recorded
        yield assertTrue(
          drafts.map(_.kind) == List(AuthAuditKinds.CertEnrolled),
          drafts.head.outcome == AuditOutcome.success,
          drafts.head.actor == Some((AuthActors.client, Some("bank-a"))),
          drafts.head.details == AuthAuditDetails.Cert(
            operation = "enroll",
            clientId = Some("bank-a"),
            serial = Some(leaf.getSerialNumber.toString(16)),
            notAfter = Some(leaf.getNotAfter.toInstant.toString),
          ),
          // the one-time token is not anywhere in it
          !drafts.head.details.toJson.toString.contains("TOKEN"),
        )
      },
      test("a renewal records cert.renewed for the client the presented certificate names") {
        val central = stub[EstCentralClient]
        val (recording, est) = audited(central)
        val presented = TestCertificates.generate(subject = "CN=bank-a,OU=tenant-a,O=Versola")
        for
          _ <- realTime
          _ <- central.renew.succeedsWith(EstCentralClient.Issued(issuedChain, None))
          _ <- est.reenroll(presented.certificate, csrBody)
          drafts <- recording.recorded
        yield assertTrue(
          drafts.map(_.kind) == List(AuthAuditKinds.CertRenewed),
          drafts.head.actor == Some((AuthActors.client, Some("bank-a"))),
          drafts.head.details.toJson.toString.contains("\"operation\":\"reenroll\""),
        )
      },
      test("what central refuses is recorded by what it says about the caller: denied for who, failure for what was asked") {
        val central = stub[EstCentralClient]
        val (recording, est) = audited(central)
        for
          _ <- central.enroll.failsWith(EstError.Unauthorized)
          _ <- est.enroll(credentials, csrBody).flip
          _ <- central.enroll.failsWith(EstError.Forbidden("chain limit"))
          _ <- est.enroll(credentials, csrBody).flip
          _ <- central.enroll.failsWith(EstError.BadRequest("wrong subject"))
          _ <- est.enroll(credentials, csrBody).flip
          drafts <- recording.recorded
        yield assertTrue(
          drafts.map(d => (d.kind, d.outcome, d.reason)) == List(
            (AuthAuditKinds.CertEnrolled, AuditOutcome.denied, Some("est_unauthorized")),
            (AuthAuditKinds.CertEnrolled, AuditOutcome.denied, Some("est_forbidden")),
            (AuthAuditKinds.CertEnrolled, AuditOutcome.failure, Some("est_bad_request")),
          ),
          // the reason central gave is not repeated: it describes the request, and may quote it
          !drafts.exists(_.details.toJson.toString.contains("wrong subject")),
        )
      },
      test("a client that exceeds its attempts is recorded as a failure, rate limited") {
        val central = stub[EstCentralClient]
        val (recording, est) = audited(central, attempts = 1)
        for
          _ <- central.enroll.failsWith(EstError.Unauthorized)
          _ <- est.enroll(credentials, csrBody).flip
          limited <- est.enroll(credentials, csrBody).flip
          drafts <- recording.recorded
        yield assertTrue(limited == EstError.TooManyRequests, drafts.map(_.reason).last == Some("est_rate_limited"))
      },
      test("a disabled EST and a failure of central itself record nothing") {
        val central = stub[EstCentralClient]
        val disabled = CapturingAuditEvents.shared()
        val down = CapturingAuditEvents.shared()
        for
          _ <- EstService.Impl(None, central, limiter(1000), disabled).enroll(credentials, csrBody).flip
          _ <- central.enroll.failsWith(RuntimeException("central unavailable"))
          _ <- EstService.Impl(ca, central, limiter(1000), down).enroll(credentials, csrBody).exit
          a <- disabled.recorded
          b <- down.recorded
        yield assertTrue(a.isEmpty, b.isEmpty)
      },
    ),
  ) @@ TestAspect.sequential
