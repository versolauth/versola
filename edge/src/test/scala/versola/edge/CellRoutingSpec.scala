package versola.edge

import versola.edge.model.*
import versola.edge.nativeapp.NativeAuthClient
import versola.util.{PrivateClientCertificate, RedirectUri, Secret, TestCertificates}
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

  private val okFiles: ClientCertificateFiles = _ => ZIO.succeed(ClientSSLCertConfig.FromClientCertFile("client.crt", "client.key"))

  private val certificateMaterial =
    PrivateClientCertificate(TestCertificates.generate(dnsName = Some("web-app.versola.test")).bundle).material.toOption.get

  private val mutualTls = ClientCredential.MutualTls(certificateMaterial)

  private val cellPins = CellsSyncClient.pinned(List(TestCertificates.generate(ca = true).certificatePem))

  /** A cell with a mutual-TLS listener of its own, told apart from `auth` by the path prefix like the others. */
  private def secured(id: String) = cell(id, "almaty", mtls = Some(s"https://auth.mtls:9443/$id-mtls")).copy(trust = cellPins)

  private val pushClient = OAuthClient(
    id = ClientId("web-app"),
    credential = secret,
    permissions = Set.empty,
    accessTokenTtl = 15.minutes,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = true,
    applicationType = ApplicationType.web,
    redirectUris = Set.empty,
  )

  private val preset = AuthorizationPreset(
    id = PresetId("test"),
    clientId = ClientId("web-app"),
    description = "test",
    redirectUri = RedirectUri("https://app.example/callback"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    postLogoutRedirectUri = None,
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val pushedJson = """{"request_uri":"urn:ietf:params:oauth:request_uri:abc","expires_in":60}"""

  /** An access token naming `cell` in its JOSE header; the payload and signature are never read by a router. */
  private def tokenOf(cell: String) =
    AccessToken(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(s"""{"alg":"RS256","cell":"$cell"}""".getBytes("UTF-8")) + ".e30.c2ln")

  private val nativeConfig = config.copy(native =
    Some(
      EdgeConfig.Native(
        authMutualTlsUrl = url("https://auth.mtls:9443/configured"),
        trustedCertificates = Set("/etc/versola/auth-mtls.pem"),
        blobKey = Secret.Bytes32(Array.fill(32)(9.toByte)),
      ),
    ),
  )

  private def native(client: Client, cells: CellTopology) = NativeAuthClient.Impl(client, nativeConfig, okFiles, table(cells))

  private def form(fields: (String, String)*) = Form(fields.map(FormField.simpleField(_, _))*)

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
        )
      },
      test("registered cells that none takes leave a new request nowhere to go, and it is not sent to the configured auth") {
        assertTrue(
          route(
            CellRouting.Placement,
            0.5,
            topology.copy(cells = Vector(one.copy(state = EdgeCellState.Draining))),
          ) == Left(CellRoutingError.NoOpenCell),
          route(CellRouting.Placement, 0.5, topology.copy(cells = Vector(one.copy(weight = 0)))) == Left(CellRoutingError.NoOpenCell),
          route(CellRouting.Placement, 0.5, CellTopology.empty) == Right(None),
        )
      },
      test("an edge in no region places among every open cell") {
        val anywhere = topology.copy(region = None, cells = Vector(three))
        assertTrue(route(CellRouting.Placement, 0.0, anywhere).toOption.flatten.map(_.id) == Some("kz-3"))
      },
    ),
    suite("native flow")(
      test("a native code, refresh token and token to revoke go to the mutual-TLS listener of the cell they name") {
        val cells = topology.copy(cells = Vector(secured("kz-1"), secured("kz-2")))
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          auth = native(client, cells)
          _ <- auth.token("app", certificateMaterial, form("grant_type" -> "authorization_code", "code" -> "v1.kz-2.abc"), Headers.empty)
          _ <- auth.token("app", certificateMaterial, form("grant_type" -> "refresh_token", "refresh_token" -> "v1.kz-1.abc"), Headers.empty)
          _ <- auth.revoke("app", certificateMaterial, form("token" -> "v1.kz-2.abc"), Headers.empty)
          _ <- auth.revoke("app", certificateMaterial, form("token" -> tokenOf("kz-1").toString), Headers.empty)
          _ <- auth.token("app", certificateMaterial, form("grant_type" -> "refresh_token", "refresh_token" -> "legacy"), Headers.empty)
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-2-mtls", "kz-1-mtls", "kz-2-mtls", "kz-1-mtls", "configured"))
      },
      test("a native pushed request is placed in an open cell and replayed once on moved; a refresh is too, a revocation is not") {
        val cells = topology.copy(cells = Vector(secured("kz-1"), secured("kz-3").copy(state = EdgeCellState.Closed)))
        for
          hosts <- serve(host => if host == "kz-1-mtls" then moved else Response.json(pushedJson).status(Status.Created))
          client <- ZIO.service[Client]
          auth = native(client, cells)
          pushed <- auth.par("app", certificateMaterial, form("scope" -> "openid"))
          refreshed <- auth.token("app", certificateMaterial, form("refresh_token" -> "v1.kz-1.abc"), Headers.empty)
          revoked <- auth.revoke("app", certificateMaterial, form("token" -> "v1.kz-1.abc"), Headers.empty)
          seen <- hosts.get
        yield assertTrue(
          seen.reverse == List("kz-1-mtls", "kz-3-mtls", "kz-1-mtls", "kz-3-mtls", "kz-1-mtls"),
          pushed.status == Status.Created,
          refreshed.status == Status.Created,
          revoked.status.code == 421,
        )
      },
      test("a native value naming an unknown cell is an invalid grant with no call made, and a cell without a pinned listener is not reached") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          unknown <- native(client, topology).token("app", certificateMaterial, form("refresh_token" -> "v1.kz-9.abc"), Headers.empty)
          unreachable <- native(client, topology).token("app", certificateMaterial, form("refresh_token" -> "v1.kz-1.abc"), Headers.empty).flip
          shut <- native(client, topology.copy(cells = Vector(secured("kz-1").copy(state = EdgeCellState.Draining))))
            .par("app", certificateMaterial, form("scope" -> "openid")).flip
          seen <- hosts.get
        yield assertTrue(
          unknown.status == Status.BadRequest,
          unknown.bodyAsString.contains("invalid_grant"),
          unreachable == NativeAuthClient.CellUnreachable("kz-1"),
          shut == CellRoutingError.NoOpenCell,
          seen.isEmpty,
        )
      },
      test("with no cell registered every native call goes to the configured listener") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          auth = native(client, CellTopology.empty)
          _ <- auth.par("app", certificateMaterial, form("scope" -> "openid"))
          _ <- auth.token("app", certificateMaterial, form("code" -> "v1.kz-1.abc"), Headers.empty)
          seen <- hosts.get
        yield assertTrue(seen == List("configured", "configured"))
      },
    ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging,
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
      test("a refresh token that names an unknown cell is an invalid grant, before any call") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          unknown <- sso.exchangeRefreshToken(RefreshToken("v1.kz-9.abc"), ClientId("web-app"), secret).flip
          malformed <- sso.exchangeRefreshToken(RefreshToken("v1.kz-1"), ClientId("web-app"), secret).flip
          seen <- hosts.get
        yield assertTrue(unknown == SSOClient.InvalidGrant, malformed == SSOClient.InvalidGrant, seen.isEmpty)
      },
      test("userinfo goes to the cell the token's header names, and to the configured auth when it names none") {
        for
          hosts <- serve(_ => Response.json("""{"sub":"u"}"""))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          _ <- sso.userInfo(tokenOf("kz-2"), SSOClient.TokenBinding.Unbound)
          _ <- sso.userInfo(AccessToken("opaque"), SSOClient.TokenBinding.Unbound)
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-2", "configured"))
      },
      test("a token whose header names a cell the edge does not have is unauthorized, and nothing is called") {
        for
          hosts <- serve(_ => Response.json("""{"sub":"u"}"""))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          error <- sso.userInfo(tokenOf("kz-9"), SSOClient.TokenBinding.Unbound).flip
          seen <- hosts.get
        yield assertTrue(error == SSOClient.UserInfoUnauthorized, seen.isEmpty)
      },
      test("a certificate client's call goes to the mutual-TLS listener of the cell that holds the user") {
        val cells = topology.copy(cells = Vector(secured("kz-1"), secured("kz-2")))
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, okFiles, noIdentity, table(cells))
          _ <- sso.exchangeAuthorizationCode(
            Code("v1.kz-2.abc"),
            CodeVerifier("v"),
            RedirectUri("https://app.example/cb"),
            ClientId("web-app"),
            mutualTls,
          )
          _ <- sso.userInfo(tokenOf("kz-1"), SSOClient.TokenBinding.Certificate(ClientId("web-app"), mutualTls))
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-2-mtls", "kz-1-mtls"))
      },
      test("a cell with no mutual-TLS listener refuses a certificate client rather than receive the certificate elsewhere") {
        for
          hosts <- serve(_ => Response.json(tokenJson))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, okFiles, noIdentity, table(topology))
          exchange <- sso.exchangeAuthorizationCode(
            Code("v1.kz-1.abc"),
            CodeVerifier("v"),
            RedirectUri("https://app.example/cb"),
            ClientId("web-app"),
            mutualTls,
          ).flip
          userinfo <- sso.userInfo(tokenOf("kz-1"), SSOClient.TokenBinding.Certificate(ClientId("web-app"), mutualTls)).flip
          seen <- hosts.get
        yield assertTrue(
          exchange == SSOClient.CellWithoutMutualTls(ClientId("web-app"), "kz-1"),
          userinfo == SSOClient.CellWithoutMutualTls(ClientId("web-app"), "kz-1"),
          seen.isEmpty,
        )
      },
      test("a pushed request is placed in an open cell, and replayed once when that cell says the user moved") {
        val placed = topology.copy(cells = Vector(one, two.copy(state = EdgeCellState.Closed), three.copy(state = EdgeCellState.Closed)))
        for
          hosts <- serve(host => if host == "kz-1" then moved else Response.json(pushedJson).status(Status.Created))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(placed))
          url <- sso.authorizeUri(preset, pushClient, "challenge", State.fromBytes(Array.fill(16)(1.toByte)), Map.empty)
          seen <- hosts.get
        yield assertTrue(seen.reverse == List("kz-1", "kz-3"), url.queryParams.map.get("request_uri").exists(_.nonEmpty))
      },
      test("a pushed request with no cell open is refused and not sent to the configured auth") {
        val shut = topology.copy(cells = Vector(one.copy(state = EdgeCellState.Draining), two.copy(state = EdgeCellState.Closed)))
        for
          hosts <- serve(_ => Response.json(pushedJson).status(Status.Created))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(shut))
          error <- sso.authorizeUri(preset, pushClient, "challenge", State.fromBytes(Array.fill(16)(1.toByte)), Map.empty).flip
          seen <- hosts.get
        yield assertTrue(error == CellRoutingError.NoOpenCell, seen.isEmpty)
      },
      test("a 421 that names no cell is a failure, not an answer handed on") {
        for
          hosts <- serve(_ => Response.text("misdirected").status(Status.fromInt(421)))
          client <- ZIO.service[Client]
          sso = SSOClient.Impl(client, config, files, noIdentity, table(topology))
          error <- exchange(sso, "v1.kz-1.abc").flip
          seen <- hosts.get
        yield assertTrue(error.getMessage.contains("421"), seen == List("kz-1"))
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
