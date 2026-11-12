package versola.edge.partner

import versola.edge.model.{ClientCredential, ClientId, EdgeId}
import versola.edge.{CentralSyncTokenService, EdgeCertificateHolder, EdgeConfig, EdgeMode}
import versola.util.{EdgeIdentityNames, PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator

/** What a partner edge reads from central's partner surface (#962 §4): the clients of its own partner with the credential the edge
  * itself holds, and `/edge/partner/me`, whose refusals mean something at startup. */
object PartnerSyncSpec extends ZIOSpecDefault:

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
    mode = EdgeMode.Partner,
  )

  private val tokens = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed("edge-token")

  private val material = PrivateClientCertificate(
    TestCertificates.generate(subject = "CN=edge-1", uris = List(EdgeIdentityNames.edgeUri("edge-1"))).bundle,
  ).material.toOption.get

  private def holder(held: Option[PrivateClientCertificate.Material]): EdgeCertificateHolder =
    new EdgeCertificateHolder:
      override def paths = None
      override def material = ZIO.succeed(held)
      override def install(chain: String, privateKeyPem: String) = ZIO.unit

  private def clientJson(id: String, authMethod: String): String =
    s"""{"id":"$id","tenantId":"t","authMethod":"$authMethod","accessTokenTtl":"PT10M","permissions":[],"requireSignedRequestObject":false,
       |"requirePushedAuthorizationRequests":true,"applicationType":"web","redirectUris":["https://shop.partner.example/cb"],"unknownToThisEdge":1}""".stripMargin

  private def serve(path: String, body: String, status: Status = Status.Ok) =
    TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "sync" / "clients" -> Handler.fromResponse(Response.json(body).status(status))))

  private val meBody =
    """{"partner":{"id":"acme","displayName":"Acme","logoUri":null,"status":"active","limits":{"maxClients":5},"reviewAt":null,"tenantId":"t"},
      |"operator":{"name":"Bank","issuer":"https://idp.example","mtlsUrl":null,"centralMtlsUrl":null},
      |"console":{"clientId":"acme-console","presetId":"acme-console"},"edge":{"id":"edge-1","publicUrl":"https://login.acme.example"}}""".stripMargin

  def spec = suite("partner sync")(
    suite("clients")(
      test("a tls_client_auth client is authenticated by the certificate of this edge, and the request goes to the partner's sync") {
        for
          _ <- serve("clients", s"""{"clients":[${clientJson("acme-shop", "tls_client_auth")}]}""")
          client <- ZIO.service[Client]
          clients <- PartnerOAuthClientsSyncClient.Impl(client, config, tokens, holder(Some(material))).getAll
        yield assertTrue(
          clients.keySet == Set(ClientId("acme-shop")),
          clients(ClientId("acme-shop")).credential == ClientCredential.MutualTls(material),
          clients(ClientId("acme-shop")).requirePushedAuthorizationRequests,
        )
      },
      test("a client of any other method is dropped: this edge holds no credential for it") {
        for
          _ <- serve("clients", s"""{"clients":[${clientJson("acme-keys", "private_key_jwt")},${clientJson("acme-secret", "client_secret")}]}""")
          client <- ZIO.service[Client]
          clients <- PartnerOAuthClientsSyncClient.Impl(client, config, tokens, holder(Some(material))).getAll
        yield assertTrue(clients.isEmpty)
      },
      test("no client is served before the edge holds a certificate") {
        for
          _ <- serve("clients", s"""{"clients":[${clientJson("acme-shop", "tls_client_auth")}]}""")
          client <- ZIO.service[Client]
          clients <- PartnerOAuthClientsSyncClient.Impl(client, config, tokens, holder(None)).getAll
        yield assertTrue(clients.isEmpty)
      },
      test("a refusal of central (the partner is suspended) leaves no client served, rather than the last it was told of") {
        for
          _ <- serve("clients", "", status = Status.Forbidden)
          client <- ZIO.service[Client]
          clients <- PartnerOAuthClientsSyncClient.Impl(client, config, tokens, holder(Some(material))).getAll
        yield assertTrue(clients.isEmpty)
      },
      test("the permissions of every client are read, served or not, as the first-party sync's are") {
        for
          _ <- serve("clients", s"""{"clients":[${clientJson("acme-keys", "private_key_jwt")}]}""")
          client <- ZIO.service[Client]
          permissions <- PartnerOAuthClientsSyncClient.Impl(client, config, tokens, holder(None)).getPermissions
        yield assertTrue(permissions.keySet == Set(ClientId("acme-keys")))
      },
    ),
    suite("/edge/partner/me")(
      test("is read as central sends it") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.json(meBody))))
          client <- ZIO.service[Client]
          me <- PartnerMeSyncClient.Impl(client, config, tokens).getAll
        yield assertTrue(me.console.presetId == "acme-console", me.partner.id == "acme", me.edge.publicUrl.contains("https://login.acme.example"))
      },
      test("401 is a kind that is not partner: the edge stops with the mode in the message") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.Unauthorized))))
          client <- ZIO.service[Client]
          error <- PartnerMeSyncClient.Impl(client, config, tokens).getAll.flip
        yield assertTrue(error.isInstanceOf[PartnerMeSyncClient.KindMismatch], error.getMessage.contains("mode = partner"))
      },
      test("403 is a partner that is suspended") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.Forbidden))))
          client <- ZIO.service[Client]
          error <- PartnerMeSyncClient.Impl(client, config, tokens).getAll.flip
        yield assertTrue(error == PartnerMeSyncClient.Suspended)
      },
      test("the first read refuses a mismatch at startup, and lets pass what is only central not answering") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.Unauthorized))))
          client <- ZIO.service[Client]
          mismatch <- ZIO.scoped(PartnerMeCheck.live.build).provideLayer(ZLayer.succeed[PartnerMeSyncClient](PartnerMeSyncClient.Impl(
            client,
            config,
            tokens,
          ))).flip
          _ <- TestClient.addRoutes(Routes(Method.GET / "edge" / "partner" / "me" -> Handler.fromResponse(Response.status(Status.BadGateway))))
          passed <- ZIO.scoped(PartnerMeCheck.live.build).provideLayer(ZLayer.succeed[PartnerMeSyncClient](PartnerMeSyncClient.Impl(
            client,
            config,
            tokens,
          ))).exit
        yield assertTrue(mismatch.isInstanceOf[PartnerMeSyncClient.KindMismatch], passed.isSuccess)
      },
    ),
    suite("the state of the partner")(
      test("a running edge reports the partner suspended once central refuses its /me, and active again when central answers") {
        val live = PartnerMe(
          partner = PartnerMe.Partner("acme", "Acme", None, "active", Json.Obj(), None, "t"),
          operator = PartnerMe.Operator("Bank", None, None, None),
          console = PartnerMe.Console("acme-console", "acme-console"),
          edge = PartnerMe.Edge("edge-1", None),
        )
        for
          refused <- Ref.make(false)
          source = new PartnerMeSyncClient:
            override def getAll = refused.get.flatMap(r => if r then ZIO.fail(PartnerMeSyncClient.Suspended) else ZIO.succeed(live))
          result <- ZIO.scoped:
            for
              state <- PartnerState.live.build.map(
                _.get[PartnerState],
              ).provideSomeLayer[Scope](ZLayer.succeed[PartnerMeSyncClient](source) ++ ZLayer.succeed(config))
              before <- state.me
              _ <- refused.set(true)
              _ <- TestClock.adjust(6.minutes)
              during <- state.me
              reachableDuring <- state.centralReachable
              _ <- refused.set(false)
              _ <- TestClock.adjust(6.minutes)
              after <- state.me
            yield (before, during, reachableDuring, after)
          (before, during, reachable, after) = result
        yield assertTrue(before.partner.status == "active", during.partner.status == "suspended", reachable, after.partner.status == "active")
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
