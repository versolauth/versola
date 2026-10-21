package versola.edge

import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import versola.edge.model.EdgeId
import versola.util.{CertificateSubject, EdgeIdentityNames, PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.{Base64, Date}

object EdgeIdentityEnrollmentSpec extends ZIOSpecDefault:

  private val edgeId = "edge-1"
  private val subject = EdgeIdentityNames.subject(edgeId = edgeId, groupId = "default")

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val authority = TestCertificates.generate(subject = "CN=Test Edge CA", ca = true)

  private def pem(der: Array[Byte]): String =
    s"-----BEGIN CERTIFICATE-----\n${Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)}\n-----END CERTIFICATE-----"

  /** What central's CA does: signs the key in the request, for the names it is given, between the two instants. */
  private def sign(csrPem: String, names: List[String], notBefore: Instant, notAfter: Instant): String =
    val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
    val builder = JcaX509v3CertificateBuilder(
      authority.certificate,
      BigInteger.valueOf(java.lang.System.nanoTime()),
      Date.from(notBefore),
      Date.from(notAfter),
      csr.getSubject,
      csr.getPublicKey,
    )
    builder.addExtension(
      Extension.subjectAlternativeName,
      false,
      GeneralNames(names.map(GeneralName(GeneralName.uniformResourceIdentifier, _)).toArray),
    )
    pem(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(authority.privateKey)).getEncoded)

  private def openHolder(layer: ULayer[EdgeConfig]): Task[EdgeCertificateHolder] =
    ZIO.scoped(EdgeCertificateHolder.live.build.map(_.get[EdgeCertificateHolder])).provide(layer)

  private def tempDirectory: Task[Path] =
    ZIO.attemptBlocking(Files.createTempDirectory("edge-identity-spec").nn)

  private def config(directory: Path, tokenFile: Option[Path]): EdgeConfig =
    EdgeConfig(
      id = EdgeId(edgeId),
      keyId = "kid-1",
      privateKey = keyPair.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(
        url = URL.decode("https://central-mtls.example").toOption.get,
        mutualTls = Some(EdgeConfig.CentralMutualTls(trustedCertificates = Set("/pin.pem"))),
        identity = Some(
          EdgeConfig.CentralIdentity(
            directory = directory.toString,
            enrollment = tokenFile.map(file => EdgeConfig.CentralEnrollment(URL.decode("https://central.example").toOption.get, file.toString)),
          ),
        ),
      ),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
      edgeUrl = URL.decode("https://edge.example").toOption.get,
      configurationCacheRefreshInterval = 5.minutes,
    )

  private val tokens: CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed("BEARER")

  /** A central that answers enrollment and renewal with a certificate for the key it was sent, valid for `lifetime`
    * from the clock's now, and remembers what it was asked. */
  private case class Calls(enroll: Ref[List[String]], renew: Ref[List[(String, String)]])

  private def central(lifetime: Duration, names: List[String] = subject.uris, status: Status = Status.Ok): ZIO[TestClient, Nothing, Calls] =
    for
      enroll <- Ref.make(List.empty[String])
      renew <- Ref.make(List.empty[(String, String)])
      _ <- TestClient.addRoutes(
        Routes(
          Method.POST / "edge" / "enroll" -> handler { (request: Request) =>
            for
              body <- request.body.asString.orDie
              _ <- enroll.update(_ :+ body)
              csr = body.fromJson[zio.json.ast.Json.Obj].toOption.flatMap(_.get("csr")).flatMap(_.asString).getOrElse("")
              now <- Clock.instant
            yield
              if status.isSuccess then
                Response.json(s"""{"certificate":${sign(csr, names, now.minusSeconds(60), now.plusSeconds(lifetime.getSeconds)).toJson}}""")
              else Response.status(status)
          },
          Method.POST / "edge" / "certificate" / "renew" -> handler { (request: Request) =>
            for
              body <- request.body.asString.orDie
              _ <- renew.update(_ :+ (request.header(Header.Authorization).map(_.renderedValue).getOrElse("") -> body))
              csr = body.fromJson[zio.json.ast.Json.Obj].toOption.flatMap(_.get("csr")).flatMap(_.asString).getOrElse("")
              now <- Clock.instant
            yield
              if status.isSuccess then
                Response.json(s"""{"certificate":${sign(csr, names, now.minusSeconds(60), now.plusSeconds(lifetime.getSeconds)).toJson}}""")
              else Response.status(status)
          },
        ),
      )
    yield Calls(enroll, renew)

  private def tokenFile(content: String): Task[Path] =
    ZIO.attemptBlocking:
      val file = Files.createTempFile("edge-enrollment-token", "").nn
      Files.writeString(file, content + "\n")
      file

  def spec = suite("edge identity")(
    suite("EdgeCertificateHolder")(
      test("a pair is kept in an owner-only directory and is what is presented") {
        for
          directory <- tempDirectory
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          before = holder.paths
          generated <- CertificateSubject.generate(subject)
          chain = sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(60), Instant.now.plusSeconds(3600))
          _ <- holder.install(chain, generated.privateKeyPem)
          permissions <- ZIO.attemptBlocking:
            val (certificate, key) = holder.paths.get
            (
              PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(key)).nn),
              PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(certificate)).nn),
            )
          material <- holder.material
        yield assertTrue(before.isEmpty, permissions == ("rw-------", "rw-------"), material.isDefined)
      },
      test("what was installed is presented again after a restart") {
        for
          directory <- tempDirectory
          layer = ZLayer.succeed(config(directory, None))
          first <- openHolder(layer)
          generated <- CertificateSubject.generate(subject)
          _ <-
            first.install(sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(60), Instant.now.plusSeconds(3600)), generated.privateKeyPem)
          second <- openHolder(layer)
        yield assertTrue(second.paths == first.paths, second.paths.isDefined)
      },
      test("a new pair never overwrites the one before it, and old ones are removed") {
        for
          directory <- tempDirectory
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          paths <- ZIO.foreach(1 to 4): _ =>
            for
              generated <- CertificateSubject.generate(subject)
              _ <- holder.install(
                sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(60), Instant.now.plusSeconds(3600)),
                generated.privateKeyPem,
              )
            yield holder.paths.get._1
          kept <- ZIO.attemptBlocking(Files.list(directory).nn.count())
        yield assertTrue(paths.distinct.size == 4, kept == 2L)
      },
      test("a pair that is not a certificate with its key is refused and the current one stays") {
        for
          directory <- tempDirectory
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          generated <- CertificateSubject.generate(subject)
          chain = sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(60), Instant.now.plusSeconds(3600))
          _ <- holder.install(chain, generated.privateKeyPem)
          current = holder.paths
          other <- CertificateSubject.generate(subject)
          failure <- holder.install(chain, other.privateKeyPem).flip
        yield assertTrue(failure.isInstanceOf[IllegalArgumentException], holder.paths == current)
      },
    ),
    suite("EdgeIdentityEnrollment")(
      test("an edge with no certificate trades its token for one that names it and its group") {
        for
          directory <- tempDirectory
          file <- tokenFile("TOKEN")
          calls <- central(lifetime = 14.days)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, Some(file))))
          _ <- EdgeIdentityEnrollment.Impl(config(directory, Some(file)), client, holder, tokens).ensureCertificate
          asked <- calls.enroll.get
          material <- holder.material
          body = asked.head.fromJson[zio.json.ast.Json.Obj].toOption.get
          csr = body.get("csr").flatMap(_.asString).get
        yield assertTrue(
          asked.size == 1,
          body.get("edgeId").flatMap(_.asString).contains(edgeId),
          body.get("token").flatMap(_.asString).contains("TOKEN"),
          CertificateSubject.matches(csr, subject).isRight,
          material.isDefined,
        )
      },
      test("an edge that has a valid certificate does not enrol again") {
        for
          directory <- tempDirectory
          file <- tokenFile("TOKEN")
          calls <- central(lifetime = 14.days)
          client <- ZIO.service[Client]
          layer = ZLayer.succeed(config(directory, Some(file)))
          holder <- openHolder(layer)
          enrollment = EdgeIdentityEnrollment.Impl(config(directory, Some(file)), client, holder, tokens)
          _ <- enrollment.ensureCertificate
          restarted <- openHolder(layer)
          _ <- EdgeIdentityEnrollment.Impl(config(directory, Some(file)), client, restarted, tokens).ensureCertificate
          asked <- calls.enroll.get
        yield assertTrue(asked.size == 1)
      },
      test("an expired certificate is not presented: the edge enrols again") {
        for
          directory <- tempDirectory
          file <- tokenFile("TOKEN")
          calls <- central(lifetime = 14.days)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, Some(file))))
          generated <- CertificateSubject.generate(subject)
          _ <- holder.install(
            sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(7200), Instant.now.minusSeconds(3600)),
            generated.privateKeyPem,
          )
          _ <- EdgeIdentityEnrollment.Impl(config(directory, Some(file)), client, holder, tokens).ensureCertificate
          asked <- calls.enroll.get
        yield assertTrue(asked.size == 1)
      },
      test("with no certificate and no enrollment configured the edge does not start, and says why") {
        for
          directory <- tempDirectory
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          failure <- EdgeIdentityEnrollment.Impl(config(directory, None), client, holder, tokens).ensureCertificate.flip
        yield assertTrue(failure.getMessage.contains("no enrollment configured"))
      },
      test("a token central refuses is an edge that does not start") {
        for
          directory <- tempDirectory
          file <- tokenFile("SPENT")
          _ <- central(lifetime = 14.days, status = Status.Unauthorized)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, Some(file))))
          failure <- EdgeIdentityEnrollment.Impl(config(directory, Some(file)), client, holder, tokens).ensureCertificate.flip
          material <- holder.material
        yield assertTrue(failure.getMessage.contains("Unauthorized") || failure.getMessage.contains("401"), material.isEmpty)
      },
      test("a certificate with a third or less of its life left is renewed, with a new key") {
        for
          directory <- tempDirectory
          calls <- central(lifetime = 14.days)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          generated <- CertificateSubject.generate(subject)
          // 30 days long, 5 left: due
          _ <- holder.install(
            sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(25 * 86400), Instant.now.plusSeconds(5 * 86400)),
            generated.privateKeyPem,
          )
          before <- holder.material
          _ <- EdgeIdentityEnrollment.Impl(config(directory, None), client, holder, tokens).renewIfDue
          after <- holder.material
          asked <- calls.renew.get
        yield assertTrue(
          asked.size == 1,
          asked.head._1.contains("BEARER"),
          after.get.privateKeyPem != before.get.privateKeyPem,
          after.get.leaf.getNotAfter.after(before.get.leaf.getNotAfter),
        )
      },
      test("a certificate with most of its life left is left alone") {
        for
          directory <- tempDirectory
          calls <- central(lifetime = 14.days)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          generated <- CertificateSubject.generate(subject)
          _ <- holder.install(
            sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(86400), Instant.now.plusSeconds(29 * 86400)),
            generated.privateKeyPem,
          )
          _ <- EdgeIdentityEnrollment.Impl(config(directory, None), client, holder, tokens).renewIfDue
          asked <- calls.renew.get
        yield assertTrue(asked.isEmpty)
      },
      test("a renewal central refuses keeps the certificate that is still valid") {
        for
          directory <- tempDirectory
          _ <- central(lifetime = 14.days, status = Status.UnprocessableEntity)
          client <- ZIO.service[Client]
          holder <- openHolder(ZLayer.succeed(config(directory, None)))
          generated <- CertificateSubject.generate(subject)
          _ <- holder.install(
            sign(generated.csrPem, subject.uris, Instant.now.minusSeconds(25 * 86400), Instant.now.plusSeconds(5 * 86400)),
            generated.privateKeyPem,
          )
          before = holder.paths
          failure <- EdgeIdentityEnrollment.Impl(config(directory, None), client, holder, tokens).renewIfDue.flip
        yield assertTrue(failure.getMessage.contains("renewal"), holder.paths == before)
      },
    ).provideSomeLayer(TestClient.layer),
  ) @@ TestAspect.silentLogging @@ TestAspect.sequential @@ TestAspect.withLiveClock
