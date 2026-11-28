package versola.edge

import versola.edge.model.EdgeId
import versola.util.Secret
import zio.*
import zio.http.*
import zio.test.*

import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator

/** What the edge makes of central's `/edge/sync/cells`: the cells as it reaches them, and the pins of each as a trust of
  * this process's own. */
object CellsSyncClientSpec extends ZIOSpecDefault:
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  private val pem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"
  private val otherPem = "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----"

  private def cell(id: String, state: String = "open", internalUrl: String = "http://auth.internal:9003", pins: String = "[]") =
    s"""{"id":"$id","regionId":"almaty","partitionId":"kz","internalUrl":"$internalUrl","mtlsUrl":"https://$id.mtls:9443","trustedCertificates":$pins,"state":"$state","weight":70}"""

  private def served(document: String) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(Response.json(document))).toRoutes)
      client <- ZIO.service[Client]
      topology <- CellsSyncClient.Impl(client, config, centralSyncTokenService).getAll
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
    yield (topology, request)

  private def pathOf(trust: Option[ClientSSLConfig]): Option[String] =
    trust.collect { case ClientSSLConfig.FromCertFile(path) => path }

  def spec = suite("CellsSyncClient")(
    test("reads the cells, their state and the region of this edge with the sync token") {
      for (topology, request) <-
          served(s"""{"cells":[${cell("kz-1")},${cell("kz-2", state = "draining")},${cell("kz-3", state = "closed")}],"region":"almaty"}""")
      yield assertTrue(
        request.url.path.encode.contains("edge/sync/cells"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        topology.region.contains("almaty"),
        topology.cells.map(c => c.id -> c.state) ==
          Vector("kz-1" -> EdgeCellState.Open, "kz-2" -> EdgeCellState.Draining, "kz-3" -> EdgeCellState.Closed),
        topology.cells.forall(c => c.weight == 70 && c.partitionId == "kz" && c.trust.isEmpty),
        topology.cells.head.mtlsUrl.map(_.encode).contains("https://kz-1.mtls:9443"),
      )
    },
    test("an answer with a state or an address this build cannot read fails the load, so the table it has is kept") {
      for
        state <- served(s"""{"cells":[${cell("kz-1", state = "paused")}],"region":null}""").exit
        address <- served(s"""{"cells":[${cell("kz-1", internalUrl = "not a url")}],"region":null}""").exit
      yield assertTrue(state.isFailure, address.isFailure)
    },
    test("the pins of a cell become a trust file of this process's own, the same one for the same pins") {
      val first = CellsSyncClient.pinned(List(pem, otherPem))
      val again = CellsSyncClient.pinned(List(otherPem, pem))
      val other = CellsSyncClient.pinned(List(pem))
      val written = pathOf(first).map(path => Files.readString(Path.of(path)))
      assertTrue(
        first == again,
        first != other,
        CellsSyncClient.pinned(Nil).isEmpty,
        written.contains(List(pem, otherPem).sorted.mkString("\n")),
        // Not a name that could be guessed and planted in a shared temporary directory.
        !pathOf(first).exists(_.contains("versola-cell-pins-")),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
