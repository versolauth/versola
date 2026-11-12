package versola.edge.partner

import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import versola.edge.EdgeCertificateHolder
import versola.util.{EdgeIdentityNames, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.{Base64, Date}

object PartnerEnrollmentSpec extends ZIOSpecDefault:

  private def address(url: String): String = Base64.getUrlEncoder.withoutPadding.encodeToString(url.getBytes(StandardCharsets.UTF_8))

  private val secret = "q3b1Xk0-_9Zr7mP2vN4cT6yH8jL5dF1sA0wE3uI9oK4"
  private val token = s"vtk1.${address("https://enroll.example")}.$secret"

  /** The subset of the edge's configuration the enrollment decides, read the way `EdgeConfig` is. */
  private case class Probe(
      id: String,
      keyId: String,
      privateKey: String,
      versolaUrl: String,
      versolaInternalUrl: Option[String],
      edgeUrl: String,
      central: ProbeCentral,
  )
  private case class ProbeCentral(url: String, identity: ProbeIdentity)
  private case class ProbeIdentity(directory: String, group: String = "default")

  private val authority = TestCertificates.generate(subject = "CN=Test Edge CA", ca = true)

  private def pem(der: Array[Byte]): String =
    s"-----BEGIN CERTIFICATE-----\n${Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)}\n-----END CERTIFICATE-----"

  /** What central's CA does with a request of a token-resolved enrollment: the key of the request, the names central chose. */
  private def sign(csrPem: String, names: List[String], notBefore: Instant, notAfter: Instant): String =
    val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
    val builder = JcaX509v3CertificateBuilder(
      authority.certificate,
      BigInteger.valueOf(java.lang.System.nanoTime()),
      Date.from(notBefore),
      Date.from(notAfter),
      org.bouncycastle.asn1.x500.X500Name("CN=edge-9"),
      csr.getPublicKey,
    )
    builder.addExtension(
      Extension.subjectAlternativeName,
      false,
      GeneralNames(names.map(GeneralName(GeneralName.uniformResourceIdentifier, _)).toArray),
    )
    pem(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(authority.privateKey)).getEncoded)

  private val names = List(EdgeIdentityNames.edgeUri("edge-9"), EdgeIdentityNames.groupUri("acme"))

  private case class Central(bodies: Ref[List[String]], paths: Ref[List[String]])

  private def central(status: Status = Status.Ok, lifetime: Duration = 14.days): ZIO[TestClient, Nothing, Central] =
    for
      bodies <- Ref.make(List.empty[String])
      paths <- Ref.make(List.empty[String])
      _ <- TestClient.addRoutes(
        Routes(
          Method.POST / "edge" / "enroll" -> handler { (request: Request) =>
            for
              body <- request.body.asString.orDie
              _ <- bodies.update(_ :+ body)
              _ <- paths.update(_ :+ request.url.encode)
              csr = body.fromJson[Json.Obj].toOption.flatMap(_.get("csr")).flatMap(_.asString).getOrElse("")
              now <- Clock.instant
            yield
              if status.isSuccess then
                Response.json(
                  Json.Obj(
                    "certificate" -> Json.Str(sign(csr, names, now.minusSeconds(60), now.plusSeconds(lifetime.getSeconds))),
                    "edge" -> Json.Obj("id" -> Json.Str("edge-9"), "group" -> Json.Str("acme")),
                    "central" -> Json.Obj("mtlsUrl" -> Json.Str("https://central-mtls.example")),
                    "auth" -> Json.Obj("issuer" -> Json.Str("https://idp.example"), "mtlsUrl" -> Json.Str("https://idp-mtls.example")),
                  ).toJson,
                )
              else Response.status(status)
          },
        ),
      )
    yield Central(bodies, paths)

  private def directory: Task[Path] = ZIO.attemptBlocking(Files.createTempDirectory("partner-enrollment-spec").nn)

  private def tokenFile(content: String): Task[Path] =
    ZIO.attemptBlocking:
      val file = Files.createTempFile("partner-enrollment-token", "").nn
      Files.writeString(file, content + "\n")
      file

  /** The file of a partner edge: what is there is what the quick start says (the mode, the address, the directory, the token). */
  private def provider(
      directory: Path,
      token: Option[Path] = None,
      environmentToken: Option[String] = None,
      extra: Map[String, String] = Map.empty,
  ): ConfigProvider =
    ConfigProvider.fromMap(
      Map(
        "mode" -> "partner",
        "edge-url" -> "https://edge.example",
        "central.identity.directory" -> directory.toString,
      ) ++ token.map("central.identity.enrollment.token-file" -> _.toString) ++
        environmentToken.map("central.identity.enrollment.token" -> _) ++ extra,
    ).kebabCase

  private def read(provider: ConfigProvider): Task[Probe] =
    provider.load(zio.config.magnolia.deriveConfig[Probe]).mapError(error => RuntimeException(error.getMessage))

  def spec = suite("partner enrollment")(
    suite("the token")(
      test("carries central's https address and a secret") {
        val parsed = EnrollmentToken.parse(token)
        assertTrue(
          parsed.map(_.enrollUrl.encode) == Right("https://enroll.example"),
          parsed.map(_.value) == Right(token),
        )
      },
      test("is read with the whitespace a file adds") {
        assertTrue(EnrollmentToken.parse(s"  $token\n").isRight)
      },
      test("keeps a port of the address and drops a trailing slash") {
        val parsed = EnrollmentToken.parse(s"vtk1.${address("https://enroll.example:8443/")}.$secret")
        assertTrue(parsed.map(_.enrollUrl.encode) == Right("https://enroll.example:8443"))
      },
      test("malformed ones are refused, each for what is wrong") {
        val refused = List(
          "",
          "vtk1",
          s"vtk1.${address("https://enroll.example")}",
          s"vtk1.${address("https://enroll.example")}.$secret.extra",
          s"vtk2.${address("https://enroll.example")}.$secret",
          s"VTK1.${address("https://enroll.example")}.$secret",
          s"${address("https://enroll.example")}.$secret",
          s"vtk1.${address("https://enroll.example")}.",
          s"vtk1..$secret",
          s"vtk1.!!!notbase64!!!.$secret",
          s"vtk1.${address("https://enroll.example")}.sec ret",
          s"vtk1.${address("not a url at all")}.$secret",
          s"vtk1.${address("https://")}.$secret",
          "plain-secret-without-structure",
        ).map(EnrollmentToken.parse)
        assertTrue(refused.forall(_.isLeft))
      },
      test("an address that is not https is refused: the token is a credential") {
        val http = EnrollmentToken.parse(s"vtk1.${address("http://enroll.example")}.$secret")
        val ftp = EnrollmentToken.parse(s"vtk1.${address("ftp://enroll.example")}.$secret")
        val bare = EnrollmentToken.parse(s"vtk1.${address("enroll.example")}.$secret")
        assertTrue(http.left.exists(_.contains("https")), ftp.isLeft, bare.isLeft)
      },
      test("an address with a query or a fragment is refused") {
        assertTrue(
          EnrollmentToken.parse(s"vtk1.${address("https://enroll.example/?next=https://evil.example")}.$secret").isLeft,
          EnrollmentToken.parse(s"vtk1.${address("https://enroll.example/#x")}.$secret").isLeft,
        )
      },
      test("a tampered token is not parsed into another address: the changed part is refused or is another address that central does not know") {
        val flipped = token.replace(address("https://enroll.example"), address("https://enroll.example").dropRight(2) + "~~")
        assertTrue(EnrollmentToken.parse(flipped).isLeft)
      },
    ),
    suite("resolving the configuration")(
      test("an edge of the first party is read as the file says, and nothing is asked of anyone") {
        for
          dir <- directory
          central <- central()
          base = ConfigProvider.fromMap(Map("mode" -> "first-party", "id" -> "e1")).kebabCase
          client <- ZIO.service[Client]
          resolved <- PartnerEnrollment.resolve(base)
          id <- resolved.load(Config.string("id"))
          asked <- central.bodies.get
        yield assertTrue(id == "e1", asked.isEmpty)
      },
      test("an edge with no mode is a first-party edge") {
        for
          resolved <- PartnerEnrollment.resolve(ConfigProvider.fromMap(Map("id" -> "e1")).kebabCase)
          id <- resolved.load(Config.string("id"))
        yield assertTrue(id == "e1")
      },
      test("enrolls with the token: its own public signing key goes to central, and the answer is what the configuration is read from") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          resolved <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          probe <- read(resolved)
          bodies <- central.bodies.get
          paths <- central.paths.get
          sent = bodies.head.fromJson[Json.Obj].toOption.get
          signingKey = sent.get("signingKey").flatMap(_.asObject).get
          kept <- Enrolled.read(dir).someOrFail(RuntimeException("enrolled.json was not written"))
          holder <- EdgeCertificateHolder.directory(dir)
          certificate <- holder.material
        yield assertTrue(
          paths == List("edge/enroll"),
          sent.get("token").flatMap(_.asString).contains(token),
          // central resolves the edge from the token: the request names none
          !sent.keys.contains("edgeId"),
          signingKey.get("kty").flatMap(_.asString).contains("RSA"),
          signingKey.get("kid").flatMap(_.asString).contains(kept.keyId),
          !signingKey.keys.exists(Set("d", "p", "q", "dp", "dq", "qi")),
          probe.id == "edge-9",
          probe.central.identity.group == "acme",
          probe.central.url == "https://central-mtls.example",
          probe.versolaUrl == "https://idp.example",
          probe.keyId == kept.keyId,
          // what the file said stays
          probe.edgeUrl == "https://edge.example",
          probe.central.identity.directory == dir.toString,
          certificate.isDefined,
        )
      },
      test("the private key never leaves the process: the request holds the public half, and the kept key is the one the configuration reads") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          resolved <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          probe <- read(resolved)
          bodies <- central.bodies.get
          kept <- Enrolled.read(dir).someOrFail(RuntimeException("enrolled.json was not written"))
          privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder.decode(probe.privateKey)),
          )
          modulus = privateKey.asInstanceOf[java.security.interfaces.RSAPrivateKey].getModulus
          n = bodies.head.fromJson[Json.Obj].toOption.get.get("signingKey").flatMap(_.asObject).flatMap(_.get("n")).flatMap(_.asString).get
          sentModulus = BigInteger(1, Base64.getUrlDecoder.decode(n))
        yield assertTrue(kept.privateKey == probe.privateKey, sentModulus == modulus, !bodies.head.contains(probe.privateKey))
      },
      test("the first certificate is asked for with a request that names nothing: the edge does not know its names yet") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          _ <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          bodies <- central.bodies.get
          csr = bodies.head.fromJson[Json.Obj].toOption.get.get("csr").flatMap(_.asString).get
          parsed = JcaPKCS10CertificationRequest(PEMParser(StringReader(csr)).readObject().asInstanceOf[PKCS10CertificationRequest])
        yield assertTrue(
          parsed.getSubject.toString == "CN=enrolling",
          parsed.getRequestedExtensions == null,
          parsed.isSignatureValid(org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder().build(parsed.getPublicKey)),
        )
      },
      test("what is kept is owner-only") {
        for
          dir <- directory
          file <- tokenFile(token)
          _ <- central()
          _ <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          permissions <- ZIO.attemptBlocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(Enrolled.FileName)).nn))
          directoryPermissions <- ZIO.attemptBlocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir).nn))
        yield assertTrue(permissions == "rw-------", directoryPermissions == "rwx------")
      },
      test("a restart reads what was kept: no token file and no request to central") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          first <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flatMap(read)
          _ <- ZIO.attemptBlocking(Files.delete(file))
          second <- PartnerEnrollment.resolve(provider(dir)).flatMap(read)
          bodies <- central.bodies.get
        yield assertTrue(bodies.size == 1, second == first)
      },
      test("a token that is still there is not spent again by a restart") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          _ <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          _ <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          bodies <- central.bodies.get
        yield assertTrue(bodies.size == 1)
      },
      test("the token may come from the environment") {
        for
          dir <- directory
          central <- central()
          probe <- PartnerEnrollment.resolve(provider(dir, environmentToken = Some(token))).flatMap(read)
          bodies <- central.bodies.get
        yield assertTrue(bodies.size == 1, probe.id == "edge-9")
      },
      test("a file that names an address for enrollment of the first party is not a partner edge's: the token has it") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          probe <- PartnerEnrollment.resolve(provider(
            dir,
            token = Some(file),
            extra = Map("central.identity.enrollment.url" -> "https://other.example"),
          )).flatMap(read)
          paths <- central.paths.get
        yield assertTrue(probe.id == "edge-9", paths == List("edge/enroll"))
      },
      test("an edge with neither a certificate nor a token does not start, and says what to do") {
        for
          dir <- directory
          central <- central()
          failure <- PartnerEnrollment.resolve(provider(dir)).flip
          bodies <- central.bodies.get
        yield assertTrue(failure.getMessage.contains("not enrolled"), failure.getMessage.contains("token"), bodies.isEmpty)
      },
      test("a token that is not one is refused before anything is sent") {
        for
          dir <- directory
          file <- tokenFile("not-a-token")
          central <- central()
          failure <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flip
          bodies <- central.bodies.get
        yield assertTrue(failure.getMessage.contains("not an enrollment token"), bodies.isEmpty)
      },
      test("a token for an address that is not https is refused before anything is sent") {
        for
          dir <- directory
          central <- central()
          failure <- PartnerEnrollment.resolve(provider(dir, environmentToken = Some(s"vtk1.${address("http://enroll.example")}.$secret"))).flip
          bodies <- central.bodies.get
        yield assertTrue(failure.getMessage.contains("https"), bodies.isEmpty)
      },
      test("a file that cannot be read is said so") {
        for
          dir <- directory
          failure <- PartnerEnrollment.resolve(provider(dir, token = Some(dir.resolve("missing")))).flip
        yield assertTrue(failure.getMessage.contains("cannot read the enrollment token"))
      },
      test("a token central refuses leaves nothing behind and the edge does not start") {
        for
          dir <- directory
          file <- tokenFile(token)
          _ <- central(status = Status.Unauthorized)
          failure <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flip
          holder <- EdgeCertificateHolder.directory(dir)
          material <- holder.material
          kept <- Enrolled.read(dir)
        yield assertTrue(failure.getMessage.contains("401") || failure.getMessage.contains("Unauthorized"), material.isEmpty, kept.isEmpty)
      },
      test("an answer that is not central's is refused") {
        for
          dir <- directory
          file <- tokenFile(token)
          _ <- TestClient.addRoutes(Routes(Method.POST / "edge" / "enroll" -> handler(Response.json("""{"certificate":"x"}"""))))
          failure <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flip
          kept <- Enrolled.read(dir)
        yield assertTrue(failure.getMessage.contains("not understood"), kept.isEmpty)
      },
      test("an answer without the addresses of central or the issuer is refused, and nothing is kept") {
        def answered(central: String, auth: String) =
          TestClient.addRoutes(
            Routes(
              Method.POST / "edge" / "enroll" -> handler { (request: Request) =>
                for
                  body <- request.body.asString.orDie
                  csr = body.fromJson[Json.Obj].toOption.flatMap(_.get("csr")).flatMap(_.asString).getOrElse("")
                  now <- Clock.instant
                yield Response.json(
                  s"""{"certificate":${sign(
                      csr,
                      names,
                      now.minusSeconds(60),
                      now.plusSeconds(86400),
                    ).toJson},"edge":{"id":"edge-9","group":"acme"},"central":$central,"auth":$auth}""",
                )
              },
            ),
          )
        for
          dir <- directory
          file <- tokenFile(token)
          _ <- answered("{}", """{"issuer":"https://idp.example"}""")
          noCentral <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flip
          _ <- answered("""{"mtlsUrl":"https://central-mtls.example"}""", "{}")
          noIssuer <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flip
          kept <- Enrolled.read(dir)
          holder <- EdgeCertificateHolder.directory(dir)
          material <- holder.material
        yield assertTrue(noCentral.getMessage.contains("mutual-TLS"), noIssuer.getMessage.contains("issuer"), kept.isEmpty, material.isEmpty)
      },
      test("a certificate that has expired is enrolled again with the token, and not without one") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          first <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          holder <- EdgeCertificateHolder.directory(dir)
          // what is in the directory is an hour past its end
          generated <- versola.util.CertificateSubject.generate(
            versola.util.EdgeIdentityNames.subject(edgeId = "edge-9", groupId = "acme"),
          )
          _ <- holder.install(
            sign(generated.csrPem, names, Instant.now.minusSeconds(7200), Instant.now.minusSeconds(3600)),
            generated.privateKeyPem,
          )
          withoutToken <- PartnerEnrollment.resolve(provider(dir)).flip
          again <- PartnerEnrollment.resolve(provider(dir, token = Some(file)))
          bodies <- central.bodies.get
        yield assertTrue(withoutToken.getMessage.contains("expired"), bodies.size == 2)
      },
      test("a partner edge's file does not carry what central decides") {
        val stated = List(
          "id" -> "x",
          "key-id" -> "x",
          "private-key" -> "x",
          "versola-url" -> "https://idp.example",
          "central.url" -> "https://central.example",
          "central.identity.group" -> "x",
        )
        ZIO.foreach(stated): (key, value) =>
          for
            dir <- directory
            file <- tokenFile(token)
            central <- central()
            failure <- PartnerEnrollment.resolve(provider(dir, token = Some(file), extra = Map(key -> value))).flip
            bodies <- central.bodies.get
          yield (failure.getMessage.contains(key), bodies.isEmpty)
        .map(results => assertTrue(results.forall(_ == (true, true))))
      },
      test("without a directory there is nowhere to keep it, and the edge does not start") {
        for
          failure <- PartnerEnrollment.resolve(ConfigProvider.fromMap(Map("mode" -> "partner")).kebabCase).flip
        yield assertTrue(failure.getMessage.contains("central.identity.directory"))
      },
      test("where auth is reached is the file's to say, and the enrollment's suggestion otherwise") {
        for
          dir <- directory
          file <- tokenFile(token)
          central <- central()
          suggested <- PartnerEnrollment.resolve(provider(dir, token = Some(file))).flatMap(read)
          stated <- PartnerEnrollment.resolve(provider(dir, extra = Map("versola-internal-url" -> "https://terminator.internal"))).flatMap(read)
        yield assertTrue(
          suggested.versolaInternalUrl.contains("https://idp-mtls.example"),
          stated.versolaInternalUrl.contains("https://terminator.internal"),
        )
      },
    ).provideSomeLayer(TestClient.layer),
  ) @@ TestAspect.silentLogging @@ TestAspect.sequential
