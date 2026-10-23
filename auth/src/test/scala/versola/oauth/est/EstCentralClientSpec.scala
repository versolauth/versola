package versola.oauth.est

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant

/** The call auth makes to central to have a certificate issued: where it goes, what it carries, and how each answer of
  * central is read. */
object EstCentralClientSpec extends ZIOSpecDefault:

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private def answering(response: Response) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(response)).toRoutes)
      client <- ZIO.service[Client]
    yield (EstCentralClient.Impl(TestEnvConfig.coreConfig, tokenService(client)), seen)

  def spec = suite("EstCentralClient")(
    test("enroll posts the token, the user name and the request to /est/enroll and reads the chain") {
      for
        (central, seen) <- answering(
          Response.json("""{"certificate":"CHAIN","chainEndsAt":"2027-10-22T00:00:00Z"}"""),
        )
        issued <- central.enroll("TOKEN", Some("bank-a"), "CSR")
        request <- seen.get.someOrFail(RuntimeException("no request"))
        body <- request.body.asString
      yield assertTrue(
        issued == EstCentralClient.Issued("CHAIN", Some(Instant.parse("2027-10-22T00:00:00Z"))),
        request.method == Method.POST,
        request.url.path.encode.endsWith("est/enroll"),
        body.contains("\"token\":\"TOKEN\""),
        body.contains("\"clientId\":\"bank-a\""),
      )
    },
    test("renew posts the presented certificate and the request to /est/renew") {
      for
        (central, seen) <- answering(Response.json("""{"certificate":"CHAIN"}"""))
        issued <- central.renew("CERT", "CSR")
        request <- seen.get.someOrFail(RuntimeException("no request"))
        body <- request.body.asString
      yield assertTrue(issued.chainEndsAt.isEmpty, request.url.path.encode.endsWith("est/renew"), body.contains("\"presentedCertificate\":\"CERT\""))
    },
    test("reads 401, 400 and 403 as what they mean, and anything else as a fault") {
      def failure(status: Status, text: String) =
        answering(Response.text(text).status(status)).flatMap((central, _) => central.renew("CERT", "CSR").flip)
      for
        unauthorized <- failure(Status.Unauthorized, "")
        badRequest <- failure(Status.BadRequest, "wrong subject")
        forbidden <- failure(Status.Forbidden, "chain limit")
        fault <- failure(Status.InternalServerError, "oops")
      yield assertTrue(
        unauthorized == EstError.Unauthorized,
        badRequest == EstError.BadRequest("wrong subject"),
        forbidden == EstError.Forbidden("chain limit"),
        !fault.isInstanceOf[EstError],
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
