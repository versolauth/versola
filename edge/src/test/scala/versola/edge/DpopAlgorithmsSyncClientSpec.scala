package versola.edge

import versola.util.Dpop
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object DpopAlgorithmsSyncClientSpec extends ZIOSpecDefault:
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

  private def served(document: String) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request] { request =>
          seen.set(Some(request)).as(Response.json(document))
        }.toRoutes,
      )
      client <- ZIO.service[Client]
      algorithms <- DpopAlgorithmsSyncClient.Impl(client, config, centralSyncTokenService).getAll
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
    yield (algorithms, request)

  def spec = suite("DpopAlgorithmsSyncClient")(
    test("reads the accepted algorithms off the metadata document central serves") {
      for
        (algorithms, request) <- served(
          """{"issuer": "https://idp.example", "dpop_signing_alg_values_supported": ["ES256", "RS256"]}""",
        )
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/server-metadata/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        algorithms == Set(Dpop.Algorithm.ES256, Dpop.Algorithm.RS256),
      )
    },
    // A deployment that has never written a metadata document gets the same set auth assumes
    // for one -- an edge that accepted nothing until someone filled the field in would refuse
    // every proof a freshly installed auth had just issued a token for.
    test("falls back to the defaults where the document does not name the field") {
      for (algorithms, _) <- served("""{"issuer": "https://idp.example"}""")
      yield assertTrue(algorithms == Dpop.Algorithm.Default)
    },
    test("drops an algorithm the document names but this build cannot verify") {
      for (algorithms, _) <- served("""{"dpop_signing_alg_values_supported": ["ES256", "EdDSA"]}""")
      yield assertTrue(algorithms == Set(Dpop.Algorithm.ES256))
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
