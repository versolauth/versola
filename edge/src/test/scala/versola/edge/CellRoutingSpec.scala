package versola.edge

import versola.edge.model.*
import versola.util.{RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator

/** Which cell a call goes to (versolauth/versola#559): the routing key of the value it presents, the `cell` header of a
  * token, or a placement by weight in the edge's own region; and what the edge does when a cell says the user moved. */
object CellRoutingSpec extends ZIOSpecDefault:

  private def url(value: String) = URL.decode(value).toOption.get

  private def cell(id: String, region: String, state: EdgeCellState = EdgeCellState.Open, weight: Int = 100, mtls: Option[String] = None) =
    EdgeCell(id, region, "kz", url(s"http://auth.internal:9003/$id"), mtls.map(url), None, state, weight)

  private val one = cell("kz-1", "almaty")
  private val two = cell("kz-2", "almaty")
  private val three = cell("kz-3", "astana")
  private val topology = CellTopology(Vector(one, two, three), region = Some("almaty"))

  private def route(routing: CellRouting, roll: Double = 0.0, topology: CellTopology = topology) = CellRouting.route(topology, routing, roll)

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = url("https://central.example")),
    versolaUrl = url("https://idp.example"),
    versolaInternalUrl = Some(url("http://auth.internal:9003/configured")),
    versolaInternalTrustedCertificates = Set.empty,
    edgeUrl = url("https://edge.example"),
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val noIdentity: EdgeIdentity = new EdgeIdentity:
    override def certificate = ZIO.succeed(None)

  private val files: ClientCertificateFiles = _ => ZIO.die(RuntimeException("no certificate expected"))

  private def table(value: CellTopology): CellTable = new CellTable:
    override def current = ZIO.succeed(value)
    override def refreshNow = ZIO.unit

  private val tokenJson =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1","refresh_token_expires_in":7200,"scope":"openid","id_token":"id-1"}"""

  private val secret = ClientCredential.ClientSecret(Secret("s3cret".getBytes("UTF-8").nn))

  /** The first path segment (the cell's own) of every call that reached a route, in order; `answer` says what each host answers. */
  private def serve(answer: String => Response): ZIO[TestClient, Nothing, Ref[List[String]]] =
    for
      hosts <- Ref.make(List.empty[String])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](request =>
          val host = request.url.path.segments.headOption.getOrElse("")
          hosts.update(host :: _).as(answer(host)),
        ).toRoutes,
      )
    yield hosts

  private def exchange(sso: SSOClient, code: String) =
    sso.exchangeAuthorizationCode(Code(code), CodeVerifier("v"), RedirectUri("https://app.example/cb"), ClientId("web-app"), secret)

  private val moved = Response.json("""{"error":"moved","cell":"kz-3"}""").status(Status.fromInt(421))

  def spec = suite("cell routing")(
    suite("which cell")(
      test("a value goes to the cell its routing key names, whatever the region of the edge") {
        assertTrue(
          route(CellRouting.ByValue("v1.kz-3.abc")) == Right(Some(three)),
          route(CellRouting.ByValue("v1.kz-1.abc")) == Right(Some(one)),
        )
      },
      test("a value issued before cells existed belongs to the deployment the edge is configured for") {
        assertTrue(route(CellRouting.ByValue("c2VjcmV0")) == Right(None))
      },
      test("a value that names a cell this deployment does not have, or does not spell its key, is refused") {
        assertTrue(
          route(CellRouting.ByValue("v1.kz-9.abc")) == Left(CellRoutingError.Unknown("kz-9")),
          route(CellRouting.ByValue("v1.kz-1")).left.exists(_.isInstanceOf[CellRoutingError.Malformed]),
        )
      },
      test("a token goes to the cell of its header, and a token without one is the legacy deployment") {
        assertTrue(
          route(CellRouting.ByHeader(Some("kz-2"))) == Right(Some(two)),
          route(CellRouting.ByHeader(None)) == Right(None),
          route(CellRouting.ByHeader(Some("kz-9"))) == Left(CellRoutingError.Unknown("kz-9")),
        )
      },
      test("a closed or draining cell still receives the calls that name it") {
        val closed = topology.copy(cells = Vector(one.copy(state = EdgeCellState.Closed), two.copy(state = EdgeCellState.Draining)))
        assertTrue(
          route(CellRouting.ByValue("v1.kz-1.a"), topology = closed).exists(_.exists(_.id == "kz-1")),
          route(CellRouting.ByValue("v1.kz-2.a"), topology = closed).exists(_.exists(_.id == "kz-2")),
        )
      },
      test("with no cell registered nothing is routed, and a value that names one is not refused") {
        assertTrue(route(CellRouting.ByValue("v1.kz-1.abc"), topology = CellTopology.empty) == Right(None))
      },
    ),
    suite("placement")(
      test("a new request goes to an open cell of the edge's region, by weight, and never to another region while one has room") {
        val weighted = topology.copy(cells = Vector(one.copy(weight = 30), two.copy(weight = 70), three))
        val picks = List(0.0, 0.29, 0.31, 0.99).map(roll => route(CellRouting.Placement, roll, weighted).toOption.flatten.map(_.id))
        assertTrue(picks == List(Some("kz-1"), Some("kz-1"), Some("kz-2"), Some("kz-2")))
      },
      test("closed, draining and zero-weight cells take no new user, and the nearest region with room is used when its own has none") {
        val local = topology.copy(cells = Vector(one.copy(state = EdgeCellState.Closed), two.copy(weight = 0), three))
        assertTrue(
          route(CellRouting.Placement, 0.5, local).toOption.flatten.map(_.id) == Some("kz-3"),
          route(CellRouting.Placement, 0.5, topology.copy(cells = Vector(one.copy(state = EdgeCellState.Draining)))) == Right(None),
        )
      },
      test("an edge in no region places among every open cell") {
        val anywhere = topology.copy(region = None, cells = Vector(three))
        assertTrue(route(CellRouting.Placement, 0.0, anywhere).toOption.flatten.map(_.id) == Some("kz-3"))
      },
    ),
    suite("SSOClient")(
      test("a code with a routing key is exchanged at its cell, and one without at the configured auth") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          _ <- exchange(sso, "v1.kz-3.abc")
          _ <- exchange(sso, "legacy")
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-3", "configured"))
      },
      test("a refresh token is routed the same way") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          _ <- sso.exchangeRefreshToken(RefreshToken("v1.kz-2.abc"), ClientId("web-app"), secret)
          seen <- hosts.get
        yield assertTrue(seen == List("kz-2"))
      },
      test("a value that names an unknown cell is refused before any call is made") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          error <- exchange(sso, "v1.kz-9.abc").flip
          seen <- hosts.get
        yield assertTrue(error == CellRoutingError.Unknown("kz-9"), seen.isEmpty)
      },
      test("a cell that answers moved is asked once more, at the cell it names") {
        for
          hosts <- serve(host => if host == "kz-1" then moved else Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          _ <- exchange(sso, "v1.kz-1.abc")
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-1", "kz-3"))
      },
      test("a second moved answer is the caller's: one replay, no chasing") {
        for
          hosts <- serve(_ => moved)
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          error <- exchange(sso, "v1.kz-1.abc").flip
          seen <- hosts.get
        yield assertTrue(error == SSOClient.CellMoved("kz-3"), seen.size == 2)
      },
      test("moved to a cell the edge does not know, or to the cell just asked, is not followed") {
        for
          hosts <- serve(_ => Response.json("""{"error":"moved","cell":"kz-9"}""").status(Status.fromInt(421)))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          unknown <- exchange(sso, "v1.kz-1.abc").flip
          seen <- hosts.get
        yield assertTrue(unknown == SSOClient.CellMoved("kz-9"), seen.size == 1)
      },
      test("an installation with no cell registered reaches the configured auth for every value") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, CellTable.empty)
          _ <- exchange(sso, "v1.kz-1.abc")
          seen <- hosts.get
        yield assertTrue(seen == List("configured"))
      },
    ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging,
  )
