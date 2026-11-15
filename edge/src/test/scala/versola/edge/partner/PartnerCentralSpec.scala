package versola.edge.partner

import versola.edge.model.EdgeId
import versola.edge.partner.PartnerCentral.Failure
import versola.edge.{CentralSyncTokenService, EdgeConfig}
import versola.util.Secret
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator

/** What the partner edge's calls to central's management routes mean to its console (#962 §4): which header is sent, and what each
  * refusal of central is. */
object PartnerCentralSpec extends ZIOSpecDefault:

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val tokens = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed("edge-token")

  private def central(routes: Routes[Any, Throwable]) =
    for
      _ <- TestClient.addRoutes(routes.handleError(_ => Response.internalServerError))
      client <- ZIO.service[Client]
    yield PartnerCentral.Impl(client, config, tokens)

  def spec = suite("PartnerCentral")(
    test("a call carries the edge's token and the staff member's, and the query and body that were asked for") {
      for
        seen <- Ref.make(Option.empty[(Option[String], Option[String], String, String)])
        c <- central(
          Routes(
            Method.POST / "edge" / "partner" / "clients" / "withdraw" -> handler { (request: Request) =>
              request.body.asString.flatMap(body =>
                seen.set(Some((
                  request.headers.get("Authorization"),
                  request.headers.get("Versola-Principal"),
                  request.url.queryParams.encode,
                  body,
                ))).as(Response.status(Status.NoContent)),
              )
            },
          ),
        )
        answer <- c.call(
          Method.POST,
          List("clients", "withdraw"),
          Map("id" -> "acme-shop"),
          Some(Json.Obj("id" -> Json.Str("acme-shop"))),
          Some("staff-token"),
        )
        recorded <- seen.get
      yield assertTrue(
        answer.status == Status.NoContent,
        recorded.exists(_._1.contains("Bearer edge-token")),
        recorded.exists(_._2.contains("staff-token")),
        recorded.exists(_._3.contains("id=acme-shop")),
        recorded.exists(_._4 == """{"id":"acme-shop"}"""),
      )
    },
    test("a call that has no principal sends none") {
      for
        seen <- Ref.make(Option.empty[Option[String]])
        c <- central(Routes(Method.GET / "edge" / "partner" / "me" -> handler((request: Request) =>
          seen.set(Some(request.headers.get("Versola-Principal"))).as(Response.ok),
        )))
        _ <- c.call(Method.GET, List("me"), Map.empty, None, None)
        recorded <- seen.get
      yield assertTrue(recorded == Some(None))
    },
    test("403 on a management call, with /me refused too, is a suspended partner") {
      for
        c <- central(
          Routes(
            Method.GET / "edge" / "partner" / "clients" -> Handler.fromResponse(Response.status(Status.Forbidden)),
            Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.Forbidden)),
          ),
        )
        failure <- c.call(Method.GET, List("clients"), Map.empty, None, Some("t")).flip
      yield assertTrue(failure == Failure.Suspended)
    },
    test("403 on a management call that /me does not share is the staff token central does not accept") {
      for
        c <- central(
          Routes(
            Method.GET / "edge" / "partner" / "clients" -> Handler.fromResponse(Response.status(Status.Forbidden)),
            Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.ok),
          ),
        )
        failure <- c.call(Method.GET, List("clients"), Map.empty, None, Some("t")).flip
      yield assertTrue(failure == Failure.PrincipalMismatch)
    },
    test("403 when even /me cannot be asked is central not being there") {
      for
        c <- central(
          Routes(
            Method.GET / "edge" / "partner" / "clients" -> Handler.fromResponse(Response.status(Status.Forbidden)),
            Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.BadGateway)),
          ),
        )
        failure <- c.call(Method.GET, List("clients"), Map.empty, None, Some("t")).flip
      yield assertTrue(failure == Failure.Unavailable)
    },
    test("5xx, 401 and a call that cannot be made are all central unavailable") {
      for
        c <- central(
          Routes(
            Method.GET / "edge" / "partner" / "clients" -> Handler.fromResponse(Response.status(Status.ServiceUnavailable)),
            Method.GET / "edge" / "partner" / "edges" -> Handler.fromResponse(Response.status(Status.Unauthorized)),
            Method.GET / "edge" / "partner" / "settings" -> Handler.fromZIO(ZIO.fail(Response.status(Status.InternalServerError))),
          ),
        )
        unavailable <- c.call(Method.GET, List("clients"), Map.empty, None, Some("t")).flip
        unauthorized <- c.call(Method.GET, List("edges"), Map.empty, None, Some("t")).flip
        broken <- c.call(Method.GET, List("settings"), Map.empty, None, Some("t")).flip
      yield assertTrue(unavailable == Failure.Unavailable, unauthorized == Failure.Unavailable, broken == Failure.Unavailable)
    },
    test("a 4xx that is central's own judgement of the request is passed on with its text") {
      for
        c <- central(Routes(Method.POST / "edge" / "partner" / "clients" -> Handler.fromResponse(
          Response.text("scope is not within the limits").status(Status.BadRequest),
        )))
        answer <- c.call(Method.POST, List("clients"), Map.empty, Some(Json.Obj()), Some("t"))
      yield assertTrue(answer.status == Status.BadRequest, answer.text == "scope is not within the limits")
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
