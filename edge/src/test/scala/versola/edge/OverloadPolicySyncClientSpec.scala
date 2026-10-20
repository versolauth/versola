package versola.edge

import versola.util.http.{InboundPolicy, OverloadPolicy}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object OverloadPolicySyncClientSpec extends ZIOSpecDefault:
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val config = EdgeConfig(
    versola.edge.model.EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(versola.util.Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(versola.util.Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  private def served(body: String, status: Status = Status.Ok) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request] { request =>
          seen.set(Some(request)).as(Response.json(body).status(status))
        }.toRoutes,
      )
      client <- ZIO.service[Client]
      policy <- OverloadPolicySyncClient.Impl(client, config, centralSyncTokenService).getAll
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
    yield (policy, request)

  private val policy = OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("enforce"), maxInFlight = Some(128))))

  def spec = suite("OverloadPolicySyncClient")(
    test("reads this edge's overload policy off central, as itself") {
      for (got, request) <- served(s"""{"overloadPolicy": ${policy.toJson}}""")
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/edges/overload/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        got == EdgeOverloadPolicy(Some(policy)),
      )
    },
    test("carries no edge id of its own: central answers from the key the call is signed with") {
      for (_, request) <- served("""{"overloadPolicy": null}""")
      yield assertTrue(request.url.queryParams.isEmpty, !request.url.path.encode.contains("edge-1"))
    },
    test("an edge with no policy set gets none, whether central says null or leaves the field out") {
      for
        (nullPolicy, _) <- served("""{"overloadPolicy": null}""")
        (absent, _) <- served("""{}""")
      yield assertTrue(nullPolicy == EdgeOverloadPolicy(None), absent == EdgeOverloadPolicy(None))
    },
    // A central that predates the endpoint has nothing to say; the edge must still start.
    test("a central without the endpoint (404) means no policy, not a failed load") {
      for (got, _) <- served("""{"error":"not found"}""", Status.NotFound)
      yield assertTrue(got == EdgeOverloadPolicy(None))
    },
    test("any other failure of central fails the load, like every other sync") {
      for exit <- served("""{}""", Status.InternalServerError).exit
      yield assertTrue(exit.isFailure)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
