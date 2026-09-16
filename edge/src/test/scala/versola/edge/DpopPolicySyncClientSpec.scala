package versola.edge

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object DpopPolicySyncClientSpec extends ZIOSpecDefault:
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

  private def served(body: String) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request] { request =>
          seen.set(Some(request)).as(Response.json(body))
        }.toRoutes,
      )
      client <- ZIO.service[Client]
      policy <- DpopPolicySyncClient.Impl(client, config, centralSyncTokenService).getAll
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
    yield (policy, request)

  def spec = suite("DpopPolicySyncClient")(
    test("reads this edge's nonce requirement off central, as itself") {
      for (policy, request) <- served("""{"requireDpopNonce": true}""")
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/edges/dpop/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        policy == DpopPolicy(requireNonce = true),
      )
    },
    // The edge is not named in the request: central answers from the key the call is signed
    // with, so there is nothing here for a caller to point at another edge's policy.
    test("carries no edge id of its own") {
      for (_, request) <- served("""{"requireDpopNonce": false}""")
      yield assertTrue(
        request.url.queryParams.isEmpty,
        !request.url.path.encode.contains("edge-1"),
      )
    },
    test("carries an edge that is not to require one") {
      for (policy, _) <- served("""{"requireDpopNonce": false}""")
      yield assertTrue(policy == DpopPolicy(requireNonce = false))
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
