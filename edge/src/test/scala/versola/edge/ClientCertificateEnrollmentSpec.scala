package versola.edge

import org.bouncycastle.asn1.x509.{Extension, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import versola.edge.model.{ClientId, EdgeId}
import versola.util.{CertificateSubject, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.json.*
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

/** The edge's side of #463 against central's sign endpoint, played by a local server that signs
  * with a CA of its own the way central does: what the edge sends, what it keeps, and when it asks
  * again. */
object ClientCertificateEnrollmentSpec extends ZIOSpecDefault:

  private val ca = TestCertificates.generate(subject = "CN=client-ca", ca = true)
  private val clientId = ClientId("mobile-app")
  private val subject = CertificateSubject("CN=mobile-app", "mobile-app", dnsNames = List("mobile-app.clients.versola.test"), uris = Nil, emailAddresses = Nil, ipAddresses = Nil)

  private case class SignRequest(clientId: String, csr: String) derives JsonCodec
  private case class SignResponse(certificate: String) derives JsonCodec

  /** @param lifetime how long the certificates the server signs are valid. */
  private final case class Central(port: Int, requests: Ref[List[SignRequest]], mode: Ref[Int], authorization: Ref[Option[String]])

  private val rsa =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private def edgeConfig(port: Int) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = rsa.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode(s"http://localhost:$port").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private def sign(csrPem: String, lifetime: Duration): String =
    val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
    val now = java.lang.System.currentTimeMillis()
    val builder = JcaX509v3CertificateBuilder(
      ca.certificate, BigInteger.valueOf(now), Date(now - 60_000), Date(now + lifetime.toMillis), csr.getSubject, csr.getPublicKey,
    )
    Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName))).foreach: san =>
      builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames.getInstance(san.getParsedValue))
    val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)))
    CertificateSubject.pem("CERTIFICATE", certificate.getEncoded)

  /** `mode`: 0 signs, 1 answers 503, 2 refuses with 422. */
  private def central(lifetime: Duration): ZIO[Scope, Throwable, Central] =
    for
      requests <- Ref.make(List.empty[SignRequest])
      mode <- Ref.make(0)
      authorization <- Ref.make(Option.empty[String])
      routes = Routes(
        Method.POST / "configuration" / "clients" / "edge-certificate" / "sign" -> handler { (req: Request) =>
          for
            text <- req.body.asString
            body <- ZIO.fromEither(text.fromJson[SignRequest]).mapError(RuntimeException(_))
            _ <- requests.update(_ :+ body) *> authorization.set(req.rawHeader("Authorization"))
            current <- mode.get
          yield current match
            case 1 => Response.text("CA down").status(Status.ServiceUnavailable)
            case 2 => Response.text("refused").status(Status.UnprocessableEntity)
            case _ => Response.json(SignResponse(sign(body.csr, lifetime)).toJson)
        },
      )
      environment <- (ZLayer.succeed(Server.Config.default.onAnyOpenPort) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized).build
      port <- Server.install(routes.handleError(e => Response.internalServerError(e.getMessage))).provideEnvironment(environment)
    yield Central(port, requests, mode, authorization)

  private val tokens = new CentralSyncTokenService:
    def getToken: UIO[String] = ZIO.succeed("edge-token")

  private def enrollment(central: Central) =
    ZIO.service[Client].flatMap(client => ClientCertificateEnrollment.make(client, edgeConfig(central.port), tokens))

  def spec = suite("ClientCertificateEnrollment")(
    test("generates its own key, sends only the request, and presents what central signed") {
      ZIO.scoped:
        for
          server <- central(1.hour)
          enrolment <- enrollment(server)
          material <- enrolment.certificateFor(clientId, subject)
          sent <- server.requests.get
          authorization <- server.authorization.get
        yield assertTrue(
          sent.size == 1,
          sent.head.clientId == "mobile-app",
          // What went over the wire is a request, never a key.
          !sent.head.csr.contains("PRIVATE KEY"),
          CertificateSubject.matches(sent.head.csr, subject) == Right(()),
          authorization.contains("Bearer edge-token"),
          material.subjectValues("san_dns") == Set("mobile-app.clients.versola.test"),
          scala.util.Try(material.leaf.verify(ca.certificate.getPublicKey)).isSuccess,
        )
    },
    test("reuses the certificate it holds while it has more than a third of its life left") {
      ZIO.scoped:
        for
          server <- central(1.hour)
          enrolment <- enrollment(server)
          first <- enrolment.certificateFor(clientId, subject)
          second <- enrolment.certificateFor(clientId, subject)
          sent <- server.requests.get
        yield assertTrue(sent.size == 1, first.leaf.getSerialNumber == second.leaf.getSerialNumber)
    },
    test("asks again with a new key once a third of its life is left") {
      ZIO.scoped:
        for
          server <- central(6.seconds)
          enrolment <- enrollment(server)
          first <- enrolment.certificateFor(clientId, subject)
          _ <- ZIO.sleep(4.seconds)
          second <- enrolment.certificateFor(clientId, subject)
          sent <- server.requests.get
        yield assertTrue(
          sent.size == 2,
          first.leaf.getSerialNumber != second.leaf.getSerialNumber,
          first.leaf.getPublicKey != second.leaf.getPublicKey,
        )
    },
    test("keeps the current certificate when central cannot renew it yet, and serves nothing it cannot enrol for") {
      ZIO.scoped:
        for
          server <- central(6.seconds)
          enrolment <- enrollment(server)
          first <- enrolment.certificateFor(clientId, subject)
          _ <- server.mode.set(1)
          _ <- ZIO.sleep(4.seconds)
          kept <- enrolment.certificateFor(clientId, subject)
          refusedNew <- enrolment.certificateFor(ClientId("other"), subject.copy(commonName = "other")).exit
        yield assertTrue(kept.leaf.getSerialNumber == first.leaf.getSerialNumber, refusedNew.isFailure)
    },
    test("fails when central refuses and there is no certificate to fall back to") {
      ZIO.scoped:
        for
          server <- central(1.hour)
          _ <- server.mode.set(2)
          enrolment <- enrollment(server)
          exit <- enrolment.certificateFor(clientId, subject).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("refused"))
    },
    test("a changed subject enrols again") {
      ZIO.scoped:
        for
          server <- central(1.hour)
          enrolment <- enrollment(server)
          _ <- enrolment.certificateFor(clientId, subject)
          _ <- enrolment.certificateFor(clientId, subject.copy(dnsNames = List("new.clients.versola.test")))
          sent <- server.requests.get
        yield assertTrue(sent.size == 2)
    },
  ).provide(Client.default) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
