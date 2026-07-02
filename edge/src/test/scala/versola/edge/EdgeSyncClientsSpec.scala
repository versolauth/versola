package versola.edge

import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import versola.edge.model.*
import versola.util.{Base64, JWT, RedirectUri, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.UUID

object EdgeSyncClientsSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
  )

  private val token = "sync-token-abc"
  private val tokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(token)

  private def bearerOf(req: Request): Option[String] =
    req.header(Header.Authorization).collect { case Header.Authorization.Bearer(v) => v.stringValue }

  private def stubbedRoute(json: String): ZIO[TestClient, Nothing, Ref[Option[Request]]] =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](req => seen.set(Some(req)).as(Response.json(json))).toRoutes,
      )
    yield seen

  def spec = suite("edge sync clients")(
    test("RolesSyncClient fetches only active roles with parsed permissions") {
      val json =
        """{"roles":[{"id":"admin","permissions":["p1","p2"],"active":true},{"id":"legacy","permissions":["p3"],"active":false}]}"""
      for
        seen <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- RolesSyncClient.Impl(client, config, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.method == Method.GET,
        req.url.encode.contains("configuration/roles/sync"),
        bearerOf(req).contains(token),
        result == Map(RoleId("admin") -> Set(PermissionId("p1"), PermissionId("p2"))),
      )
    },
    test("PermissionsSyncClient maps permission ids to endpoint ids") {
      val endpointId = UUID.fromString("018f0f2a-1c7b-7000-8000-000000000401")
      val json = s"""{"permissions":[{"id":"perm-1","endpointIds":["$endpointId"]}]}"""
      for
        seen <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- PermissionsSyncClient.Impl(client, config, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.url.encode.contains("configuration/permissions/sync"),
        bearerOf(req).contains(token),
        result == Map(PermissionId("perm-1") -> Set(ResourceEndpointId(endpointId))),
      )
    },
    test("ResourcesSyncClient keys resources by alias") {
      val resource = Resource(
        id = ResourceId(1),
        alias = "users-api",
        resource = URL.decode("http://backend.local").toOption.get,
        endpoints = Vector(
          ResourceEndpoint(
            id = ResourceEndpointId(UUID.fromString("018f0f2a-1c7b-7000-8000-000000000401")),
            method = "GET",
            path = "/users",
            fetchUserInfo = false,
            allow = None,
            inject = Vector.empty,
          ),
        ),
      )
      val json = s"""{"resources":[${resource.toJson}]}"""
      for
        seen <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- ResourcesSyncClient.Impl(client, config, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.url.encode.contains("configuration/resources/sync"),
        result.keySet == Set("users-api"),
        result("users-api").id == ResourceId(1),
        result("users-api").endpoints.map(_.path) == Vector("/users"),
      )
    },
    test("AuthorizationPresetsSyncClient keys presets by id") {
      val preset = AuthorizationPreset(
        id = PresetId("preset-1"),
        clientId = ClientId("web-app"),
        description = "default",
        redirectUri = RedirectUri("https://app.example/complete"),
        postLoginRedirectUri = RedirectUri("https://app.example/home"),
        scope = Set("openid"),
        responseType = "code",
        uiLocales = None,
        customParameters = Map.empty,
        cookieDomain = Some("app.example"),
        cookiePath = Some("/"),
      )
      val json = s"""{"presets":[${preset.toJson}]}"""
      for
        seen <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- AuthorizationPresetsSyncClient.Impl(client, config, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.url.encode.contains("configuration/auth-request-presets/sync"),
        result.keySet == Set(PresetId("preset-1")),
        result(PresetId("preset-1")).clientId == ClientId("web-app"),
        result(PresetId("preset-1")).scope == Set("openid"),
      )
    },
    test("OAuthClientsSyncClient decrypts the client secret and parses permissions") {
      val secretBytes = "s3cr3t".getBytes("UTF-8")
      for
        security <- ZIO.service[SecurityService]
        encrypted <- security.encryptRsa(secretBytes, publicKey)
        encoded = Base64.urlEncode(encrypted)
        json = s"""{"clients":[{"id":"web-app","secret":"$encoded","permissions":["perm-1"]}]}"""
        seen <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- OAuthClientsSyncClient.Impl(client, config, security, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.url.encode.contains("configuration/clients/sync"),
        bearerOf(req).contains(token),
        result.contains(ClientId("web-app")),
        result(ClientId("web-app")).secret.sameElements(secretBytes),
        result(ClientId("web-app")).permissions == Set(PermissionId("perm-1")),
      )
    },
    test("OAuthClientsSyncClient omits clients that have no secret") {
      val json = """{"clients":[{"id":"ghost","secret":null,"permissions":[]}]}"""
      for
        security <- ZIO.service[SecurityService]
        _ <- stubbedRoute(json)
        client <- ZIO.service[Client]
        result <- OAuthClientsSyncClient.Impl(client, config, security, tokenService).getAll
      yield assertTrue(result.isEmpty)
    },
    test("JwksSyncClient fetches and parses the JWKS document") {
      val rsaKey = RSAKey.Builder(publicKey).keyID(config.keyId).build()
      val jwks = JWT.PublicKeys(JWKSet(rsaKey))
      for
        seen <- stubbedRoute(jwks.toString)
        client <- ZIO.service[Client]
        result <- JwksSyncClient.Impl(client, config, tokenService).getAll
        req <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        req.url.encode.contains("configuration/jwks/sync"),
        bearerOf(req).contains(token),
        result.active.id == config.keyId,
      )
    },
  ).provideLayer(TestClient.layer ++ (SecureRandom.live >>> SecurityService.live)) @@ TestAspect.silentLogging
