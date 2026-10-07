package versola.central.configuration.clients.certificates

import versola.central.CentralConfig.CertManagerConfig
import versola.util.TestCertificates
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/** The Kubernetes API as cert-manager's `CertificateRequest` uses it, played by a local server:
  * what central sends, and how it reads the answer -- ready, still pending, denied. */
object CertManagerIssuerSpec extends ZIOSpecDefault:

  private val certificate = TestCertificates.generate(subject = "CN=issued").certificatePem
  private val request = CertificateSigningRequest("-----BEGIN CERTIFICATE REQUEST-----\nAA==\n-----END CERTIFICATE REQUEST-----\n", "mobile-app", List("a.test"), Nil, Nil, Nil, 336.hours)

  private final case class Api(port: Int, created: Ref[List[Json.Obj]], deleted: Ref[List[String]], polls: Ref[Int], authorization: Ref[Option[String]])

  /** @param statusOf what the GET answers on the n-th poll */
  private def api(statusOf: Int => Json): ZIO[Scope, Throwable, Api] =
    for
      created <- Ref.make(List.empty[Json.Obj])
      deleted <- Ref.make(List.empty[String])
      polls <- Ref.make(0)
      authorization <- Ref.make(Option.empty[String])
      routes = Routes(
        Method.POST / "apis" / "cert-manager.io" / "v1" / "namespaces" / "versola" / "certificaterequests" ->
          handler { (req: Request) =>
            req.body.asString.flatMap(text => ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))).flatMap: body =>
              created.update(_ :+ body) *> authorization.set(req.rawHeader("Authorization")) *>
                ZIO.succeed(Response.json("""{"metadata":{"name":"versola-client-x7"}}""").status(Status.Created))
          },
        Method.GET / "apis" / "cert-manager.io" / "v1" / "namespaces" / "versola" / "certificaterequests" / "versola-client-x7" ->
          handler { (_: Request) => polls.updateAndGet(_ + 1).map(n => Response.json(statusOf(n).toJson)) },
        Method.DELETE / "apis" / "cert-manager.io" / "v1" / "namespaces" / "versola" / "certificaterequests" / "versola-client-x7" ->
          handler { (_: Request) => deleted.update(_ :+ "versola-client-x7").as(Response.ok) },
      )
      environment <- (ZLayer.succeed(Server.Config.default.onAnyOpenPort) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized).build
      port <- Server.install(routes.handleError(e => Response.internalServerError(e.getMessage))).provideEnvironment(environment)
    yield Api(port, created, deleted, polls, authorization)

  private def issuer(port: Int): ZIO[Client, Throwable, ClientCertificateIssuer] =
    for
      client <- ZIO.service[Client]
      token <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("sa-token", ""), "service-account-token"))
      ca <- ZIO.attemptBlocking(Files.writeString(Files.createTempFile("sa-ca", ".crt"), certificate))
      issuer <- CertManagerIssuer.make(
        CertManagerConfig(issuerName = "versola-client-ca", namespace = Some("versola"), apiUrl = s"http://localhost:$port", tokenPath = token.toString, caPath = ca.toString),
        client,
      )
    yield issuer

  private def ready(pem: String) = Json.Obj("status" -> Json.Obj(
    "conditions" -> Json.Arr(Json.Obj("type" -> Json.Str("Ready"), "status" -> Json.Str("True"))),
    "certificate" -> Json.Str(Base64.getEncoder.encodeToString(pem.getBytes(StandardCharsets.UTF_8))),
  ))

  private val pending = Json.Obj("status" -> Json.Obj("conditions" -> Json.Arr()))

  def spec = suite("CertManagerIssuer")(
    test("creates a request for the issuer, waits for it, and returns the certificate") {
      ZIO.scoped:
        for
          server <- api(n => if n < 3 then pending else ready(certificate))
          issuer <- issuer(server.port)
          chain <- issuer.sign(request)
          created <- server.created.get
          deleted <- server.deleted.get
          authorization <- server.authorization.get
          spec = created.head.get("spec").flatMap(_.asObject).get
        yield assertTrue(
          chain == certificate,
          created.size == 1,
          spec.get("issuerRef").flatMap(_.asObject).flatMap(_.get("name")).flatMap(_.asString).contains("versola-client-ca"),
          spec.get("issuerRef").flatMap(_.asObject).flatMap(_.get("kind")).flatMap(_.asString).contains("ClusterIssuer"),
          spec.get("duration").flatMap(_.asString).contains("336h0m0s"),
          spec.get("usages").flatMap(_.asArray).exists(_.flatMap(_.asString).contains("client auth")),
          spec.get("request").flatMap(_.asString).map(b => String(Base64.getDecoder.decode(b))).contains(request.csrPem),
          authorization.contains("Bearer service-account-token"),
          deleted == List("versola-client-x7"),
        )
    },
    test("fails with cert-manager's reason when the request is denied") {
      ZIO.scoped:
        for
          server <- api(_ => Json.Obj("status" -> Json.Obj("conditions" -> Json.Arr(
            Json.Obj("type" -> Json.Str("Denied"), "status" -> Json.Str("True"), "message" -> Json.Str("not allowed by policy")),
          ))))
          issuer <- issuer(server.port)
          exit <- issuer.sign(request).exit
          deleted <- server.deleted.get
        yield assertTrue(exit.isFailure, exit.toString.contains("not allowed by policy"), deleted.nonEmpty)
    },
    test("fails when the issuer reports the request Failed") {
      ZIO.scoped:
        for
          server <- api(_ => Json.Obj("status" -> Json.Obj("conditions" -> Json.Arr(
            Json.Obj("type" -> Json.Str("Ready"), "status" -> Json.Str("False"), "reason" -> Json.Str("Failed"), "message" -> Json.Str("issuer unavailable")),
          ))))
          issuer <- issuer(server.port)
          exit <- issuer.sign(request).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("issuer unavailable"))
    },
  ).provide(Client.default) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
