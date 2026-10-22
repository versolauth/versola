package versola.oauth.est

import org.scalamock.stubs.ZIOStubs
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant
import java.util.Base64

/** What a client sees of RFC 7030: the formats, the credentials, and which listener serves what. A request carrying the
  * client's certificate cannot be made here (the test client has no TLS session), so the re-enrolment that works is the
  * e2e spec's; what is shown here is that it is refused without one. */
object EstControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val endsAt = Instant.parse("2027-10-22T00:00:00Z")
  private val pkcs7 = "MIIB...".getBytes

  private def est(service: EstService, routes: Routes[EstController.Env, Throwable]): ZIO[Client & TestClient & Scope, Throwable, Client] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(Observability.handleErrors(routes.provideEnvironment(ZEnvironment[EstService](service) ++ tracing)))
    yield client

  private def enrol(headers: Header*)(contentType: String = "application/pkcs10", body: String = "CSR") =
    Request.post(URL.root / ".well-known" / "est" / "simpleenroll", Body.fromString(body))
      .addHeader(Header.Custom("Content-Type", contentType))
      .addHeaders(Headers(headers))

  private val basic = Header.Authorization.Basic("bank-a", "TOKEN")

  def spec = suite("EstController")(
    suite("simpleenroll")(
      test("answers a PKCS#10 with a certs-only PKCS#7 in base64, and says when the chain ends") {
        val service = stub[EstService]
        for
          _ <- service.enroll.succeedsWith(EstService.Issued(pkcs7, Some(endsAt)))
          client <- est(service, EstController.routes)
          response <- client.batched(enrol(basic)())
          body <- response.body.asArray
        yield assertTrue(
          response.status == Status.Ok,
          response.rawHeader("Content-Type").contains("application/pkcs7-mime; smime-type=certs-only"),
          response.rawHeader("Content-Transfer-Encoding").contains("base64"),
          response.rawHeader("Versola-Chain-Ends-At").contains(endsAt.toString),
          body.toList == pkcs7.toList,
          service.enroll.calls == List((EstService.Credentials(Some("bank-a"), "TOKEN"), "CSR")),
        )
      },
      test("reads a Bearer token too, which carries no user name") {
        val service = stub[EstService]
        for
          _ <- service.enroll.succeedsWith(EstService.Issued(pkcs7, None))
          client <- est(service, EstController.routes)
          response <- client.batched(enrol(Header.Authorization.Bearer("TOKEN"))())
        yield assertTrue(response.status == Status.Ok, service.enroll.calls.map(_._1) == List(EstService.Credentials(None, "TOKEN")))
      },
      test("without HTTP credentials it is 401 asking for them (§3.2.3), and central is not asked") {
        val service = stub[EstService]
        for
          client <- est(service, EstController.routes)
          response <- client.batched(enrol()())
        yield assertTrue(
          response.status == Status.Unauthorized,
          response.rawHeader("WWW-Authenticate").exists(_.startsWith("Basic")),
          service.enroll.calls.isEmpty,
        )
      },
      test("a bad token is 401 as well, and a request of another media type is 400") {
        val service = stub[EstService]
        for
          _ <- service.enroll.failsWith(EstError.Unauthorized)
          client <- est(service, EstController.routes)
          bad <- client.batched(enrol(basic)())
          json <- client.batched(enrol(basic)(contentType = "application/json"))
        yield assertTrue(bad.status == Status.Unauthorized, json.status == Status.BadRequest, service.enroll.calls.size == 1)
      },
      test("a request too large for a PKCS#10 is refused before it is read further") {
        val service = stub[EstService]
        for
          client <- est(service, EstController.routes)
          response <- client.batched(enrol(basic)(body = "A" * (EstController.MaxBody + 1)))
        yield assertTrue(response.status == Status.BadRequest, service.enroll.calls.isEmpty)
      },
      test("a refusal of central is 400 or 403 with the reason in plain text") {
        val service = stub[EstService]
        for
          _ <- service.enroll.failsWith(EstError.BadRequest("the subject is CN=bank-a"))
          client <- est(service, EstController.routes)
          bad <- client.batched(enrol(basic)())
          badText <- bad.body.asString
          _ <- service.enroll.failsWith(EstError.Forbidden("chain limit"))
          forbidden <- client.batched(enrol(basic)())
        yield assertTrue(bad.status == Status.BadRequest, badText == "the subject is CN=bank-a", forbidden.status == Status.Forbidden)
      },
      test("is 404 when EST is not enabled") {
        val service = stub[EstService]
        for
          _ <- service.enroll.failsWith(EstError.Disabled)
          client <- est(service, EstController.routes)
          response <- client.batched(enrol(basic)())
        yield assertTrue(response.status == Status.NotFound)
      },
    ),
    suite("simplereenroll")(
      test("is not served on the main listener: a certificate in a header is not a client certificate") {
        val service = stub[EstService]
        for
          client <- est(service, EstController.routes)
          response <- client.batched(
            Request.post(URL.root / ".well-known" / "est" / "simplereenroll", Body.fromString("CSR"))
              .addHeader(Header.Custom("Content-Type", "application/pkcs10"))
              .addHeader(Header.Custom("ssl-client-cert", "-----BEGIN CERTIFICATE-----")),
          )
        yield assertTrue(response.status == Status.NotFound, service.reenroll.calls.isEmpty)
      },
      test("is refused without a certificate from the TLS session, whatever else the request says") {
        val service = stub[EstService]
        for
          client <- est(service, EstController.reenrollmentRoutes)
          response <- client.batched(
            Request.post(URL.root / ".well-known" / "est" / "simplereenroll", Body.fromString("CSR"))
              .addHeader(Header.Custom("Content-Type", "application/pkcs10"))
              .addHeader(basic),
          )
        yield assertTrue(response.status == Status.Unauthorized, service.reenroll.calls.isEmpty)
      },
      test("simpleenroll is not served on the mutual-TLS listener") {
        val service = stub[EstService]
        for
          client <- est(service, EstController.reenrollmentRoutes)
          response <- client.batched(enrol(basic)())
        yield assertTrue(response.status == Status.NotFound, service.enroll.calls.isEmpty)
      },
    ),
    suite("cacerts and csrattrs")(
      test("cacerts is open (§4.1.2) and served on both listeners") {
        val service = stub[EstService]
        for
          _ <- service.caCertificates.succeedsWith(pkcs7)
          client <- est(service, EstController.routes ++ EstController.reenrollmentRoutes)
          response <- client.batched(Request.get(URL.root / ".well-known" / "est" / "cacerts"))
          body <- response.body.asArray
        yield assertTrue(
          response.status == Status.Ok,
          response.rawHeader("Content-Type").contains("application/pkcs7-mime; smime-type=certs-only"),
          body.toList == pkcs7.toList,
        )
      },
      test("csrattrs is 204 (§4.5.2: no attributes to ask for)") {
        val service = stub[EstService]
        for
          _ <- service.caCertificates.succeedsWith(pkcs7)
          client <- est(service, EstController.routes)
          response <- client.batched(Request.get(URL.root / ".well-known" / "est" / "csrattrs"))
        yield assertTrue(response.status == Status.NoContent)
      },
      test("both are 404 when EST is not enabled") {
        val service = stub[EstService]
        for
          _ <- service.caCertificates.failsWith(EstError.Disabled)
          client <- est(service, EstController.routes)
          cacerts <- client.batched(Request.get(URL.root / ".well-known" / "est" / "cacerts"))
          csrattrs <- client.batched(Request.get(URL.root / ".well-known" / "est" / "csrattrs"))
        yield assertTrue(cacerts.status == Status.NotFound, csrattrs.status == Status.NotFound)
      },
    ),
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging @@ TestAspect.sequential
