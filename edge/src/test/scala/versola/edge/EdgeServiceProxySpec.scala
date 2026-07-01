package versola.edge

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.ZIOStubs
import versola.edge.login.LoginRepository
import versola.edge.model.*
import versola.util.cel.CelEvaluator
import versola.util.{JWT, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.{Collections, Date, UUID}
import javax.crypto.spec.SecretKeySpec

object EdgeServiceProxySpec extends ZIOSpecDefault, ZIOStubs:

  private val presetId = PresetId("preset-default")
  private val clientId = ClientId("web-app")
  private val backendUrl = URL.decode("http://backend.local").toOption.get

  private val preset = AuthorizationPreset(
    id = presetId, clientId = clientId, description = "default",
    redirectUri = versola.util.RedirectUri("https://app.example/complete"),
    postLoginRedirectUri = versola.util.RedirectUri("https://app.example/home"),
    scope = Set("openid"), responseType = "code", uiLocales = None,
    customParameters = Map.empty, cookieDomain = Some("app.example"), cookiePath = Some("/"),
  )

  private val oauthClient = OAuthClient(id = clientId, secret = Secret(Array.fill(48)(1.toByte)), permissions = Set.empty)

  class Env:
    val secureRandom = stub[SecureRandom]
    val loginRepository = stub[LoginRepository]
    val ssoClient = stub[SSOClient]
    val jwksService = stub[JwksService]
    val refreshTokenRepository = stub[session.EdgeRefreshTokenRepository]
    val permissionService = stub[PermissionService]

    val resourceCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[String, Resource])))
    val presetCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset])))
    val clientCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ClientId, OAuthClient])))
    val resourceService = ResourceService.Impl(resourceCache)
    val clientService = OAuthClientService.Impl(presetCache, clientCache)
    val celEvaluator = CelEvaluator.Impl(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty)))

    private val keyPair =
      val gen = KeyPairGenerator.getInstance("RSA").nn
      gen.initialize(2048)
      gen.generateKeyPair().nn

    val edgeConfig = EdgeConfig(
      id = EdgeId("edge-1"),
      keyId = "kid-1",
      privateKey = keyPair.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(
        url = URL.decode("https://central.example").toOption.get,
      ),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
    )

    val publicKeys: JWT.PublicKeys =
      val rsaKey = RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(edgeConfig.keyId).build()
      JWT.PublicKeys(JWKSet(rsaKey))

    def signToken(
        sub: String = "user-1",
        role: String = "admin",
        ttlSeconds: Long = 600,
        clientId: String = "user-1-client",
        roles: List[String] = Nil,
        jti: String = UUID.randomUUID().toString,
    ): Task[AccessToken] =
      Clock.instant.flatMap { now =>
        ZIO.attemptBlocking {
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(edgeConfig.keyId)
            .`type`(JOSEObjectType("at+jwt"))
            .build()
          val builder = JWTClaimsSet.Builder()
            .issuer("test")
            .subject(sub)
            .audience(Collections.singletonList("test"))
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("client_id", clientId)
            .claim("role", role)
          val javaRoles = java.util.ArrayList[String]()
          roles.foreach(javaRoles.add)
          builder.claim("roles", javaRoles)
          val jwt = SignedJWT(header, builder.build())
          jwt.sign(RSASSASigner(edgeConfig.privateKey))
          AccessToken(jwt.serialize())
        }
      }

    def setupDefaults(): UIO[Unit] =
      for
        _ <- jwksService.getPublicKeys.succeedsWith(publicKeys)
        _ <- permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- refreshTokenRepository.find.succeedsWith(None)
      yield ()

    def withResources(values: Resource*): UIO[Unit] =
      resourceCache.set(values.map(r => r.alias -> r).toMap)

    def withPresets(values: AuthorizationPreset*): UIO[Unit] =
      presetCache.set(values.map(p => p.id -> p).toMap)

    def withClients(values: OAuthClient*): UIO[Unit] =
      clientCache.set(values.map(c => c.id -> c).toMap)

    def buildService(httpClient: Client, security: SecurityService): EdgeService =
      EdgeService.Impl(
        clientService, resourceService, celEvaluator, secureRandom,
        loginRepository, ssoClient, security, httpClient, edgeConfig,
        refreshTokenRepository, jwksService, permissionService,
      )

  private val securityServiceLayer: ULayer[SecurityService] =
    SecureRandom.live >>> SecurityService.live

  def spec = suite("EdgeService.proxy")(
    proxySuite,
  ).provideLayer(TestClient.layer ++ securityServiceLayer) @@ TestAspect.silentLogging

  private def usersEndpoint(allow: Option[String] = None,
                             inject: Vector[InjectRule] = Vector.empty,
                             fetchUserInfo: Boolean = false) =
    ResourceEndpoint(
      id = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000401")),
      method = "GET", path = "/users", fetchUserInfo = fetchUserInfo, allow = allow, inject = inject
    )

  private def userByIdEndpoint(allow: Option[String] = None, inject: Vector[InjectRule] = Vector.empty) =
    ResourceEndpoint(
      id = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000402")),
      method = "GET", path = "/users/{id}", fetchUserInfo = false, allow = allow, inject = inject,
    )

  private def createUserEndpoint(allow: Option[String] = None, inject: Vector[InjectRule] = Vector.empty) =
    ResourceEndpoint(
      id = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000403")),
      method = "POST", path = "/users", fetchUserInfo = false, allow = allow, inject = inject,
    )

  private def usersResource(endpoints: ResourceEndpoint*) =
    Resource(id = ResourceId(1), alias = "users-api", resource = backendUrl, endpoints = endpoints.toVector)

  private def captureUpstream(status: Status = Status.Ok, body: String = "ok"): ZIO[TestClient, Nothing, Ref[Option[Request]]] =
    for
      capture <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoute(Method.ANY / trailing -> handler { (req: Request) =>
        capture.set(Some(req)).as(Response(status = status, body = Body.fromString(body)))
      })
    yield capture

  private def sessionCookie(value: String): Cookie.Request = Cookie.Request(EdgeSessionCookie.name, value)

  private val proxySuite = suite("scenarios")(
    test("returns 401 when EDGE_SESSION cookie is missing") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), Request.get(URL.empty / "users"))
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("returns 404 when resource alias is unknown") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken()
        request = Request.get(URL.empty / "ghost").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("ghost", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.NotFound)
    },
    test("returns 404 when endpoint method/path is unknown") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        request = Request.post(URL.empty / "users", Body.empty).addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.NotFound)
    },
    test("returns 403 when allow expression evaluates to false") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint(allow = Some("token.role == 'guest'"))))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("forwards upstream call with bearer token and injected header when allow passes") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("token.role == 'admin'"),
        inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream(body = "users-payload")
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        body <- response.body.asString
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        body == "users-payload",
        upstream.exists(_.header(Header.Authorization).exists(_.renderedValue == "Bearer " + token)),
        upstream.exists(_.headers.get("x-user").contains("user-1")),
        upstream.exists(_.headers.get(Header.Cookie.name).isEmpty),
      )
    },
    test("returns 401 when access token is expired and no refresh token available") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("matches parameterized path and exposes path parameters in CEL context") {
      val env = new Env
      val endpoint = userByIdEndpoint(
        allow = Some("request.path.id == token.sub"),
        inject = Vector(InjectRule(InjectTarget.header, "x-resource-id", "request.path.id")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users" / "user-1").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users/user-1"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-resource-id").contains("user-1")),
      )
    },
    test("returns 403 when path parameter does not satisfy allow expression") {
      val env = new Env
      val endpoint = userByIdEndpoint(allow = Some("request.path.id == token.sub"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users" / "intruder").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users/intruder"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("exposes JSON body in CEL context and forwards body injects") {
      val env = new Env
      val endpoint = createUserEndpoint(
        allow = Some("request.body.action == 'create'"),
        inject = Vector(InjectRule(InjectTarget.body, "actor", "token.sub")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        body = Json.Obj("action" -> Json.Str("create"), "name" -> Json.Str("Alice")).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
        forwarded <- ZIO.foreach(upstream)(_.body.asString).map(_.getOrElse(""))
        parsed = forwarded.fromJson[Json.Obj].toOption
      yield assertTrue(
        response.status == Status.Ok,
        parsed.exists(_.fields.exists((k, v) => k == "actor" && v == Json.Str("user-1"))),
        parsed.exists(_.fields.exists((k, v) => k == "name" && v == Json.Str("Alice"))),
      )
    },
    test("returns 403 when JSON body does not satisfy allow expression") {
      val env = new Env
      val endpoint = createUserEndpoint(allow = Some("request.body.action == 'create'"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        body = Json.Obj("action" -> Json.Str("delete")).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("accepts Authorization: Bearer header when EDGE_SESSION cookie is absent") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addHeader(Header.Authorization.Bearer(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).exists(_.renderedValue == "Bearer " + token)),
      )
    },
    test("returns 401 when bearer header token is expired (no refresh attempt)") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        request = Request.get(URL.empty / "users").addHeader(Header.Authorization.Bearer(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        env.refreshTokenRepository.find.calls.isEmpty,
      )
    },
    test("forwards request when user role grants access to the endpoint") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.refreshTokenRepository.find.succeedsWith(None)
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(roles = List("editor"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.isDefined,
        env.permissionService.getAllowedEndpointsForRoles.calls == List(List("editor")),
        env.permissionService.getAllowedEndpointsForClient.calls.isEmpty,
      )
    },
    test("returns 403 when user role allow-list is non-empty but does not contain the endpoint") {
      val env = new Env
      val endpoint = usersEndpoint()
      val otherEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000999"))
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(otherEndpointId))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.refreshTokenRepository.find.succeedsWith(None)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(roles = List("guest"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("uses client permissions when access token is a service token (sub == client_id)") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set(endpoint.id))
        _ <- env.refreshTokenRepository.find.succeedsWith(None)
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(sub = "svc-1", clientId = "svc-1")
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.isDefined,
        env.permissionService.getAllowedEndpointsForClient.calls == List(ClientId("svc-1")),
        env.permissionService.getAllowedEndpointsForRoles.calls.isEmpty,
      )
    },
    test("exposes request.query (first value) and request.queryAll (all values) in CEL context") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("request.query['tenant'] == 'acme'"),
        inject = Vector(
          InjectRule(InjectTarget.header, "x-first-tag", "request.queryAll['tag'][0]"),
          InjectRule(InjectTarget.header, "x-second-tag", "request.queryAll['tag'][1]"),
        ),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        url = (URL.empty / "users").addQueryParam("tenant", "acme").addQueryParam("tag", "a").addQueryParam("tag", "b")
        request = Request.get(url).addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-first-tag").contains("a")),
        upstream.exists(_.headers.get("x-second-tag").contains("b")),
      )
    },
    test("exposes request.headers (first value) and request.headersAll (all values) in CEL context") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("request.headers['x-tenant'] == 'acme'"),
        inject = Vector(InjectRule(InjectTarget.header, "x-trace", "request.headersAll['x-trace-id'][0]")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))
          .addHeader("x-tenant", "acme")
          .addHeader("x-trace-id", "trace-1")
          .addHeader("x-trace-id", "trace-2")
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-trace").contains("trace-1")),
      )
    },
    test("appends injected query params to upstream URL (InjectTarget.query)") {
      val env = new Env
      val endpoint = usersEndpoint(
        inject = Vector(InjectRule(InjectTarget.query, "actor", "token.sub")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.url.queryParams.getAll("actor") == Chunk("user-1")),
      )
    },
    test("skips allow check when expression is empty or whitespace") {
      val env = new Env
      val endpoint = usersEndpoint(allow = Some("   "))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.isDefined,
      )
    },
    test("strips upstream Set-Cookie headers from the response") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- TestClient.addRoute(Method.ANY / trailing -> handler { (_: Request) =>
          Response(status = Status.Ok, body = Body.fromString("ok"))
            .addCookie(Cookie.Response("upstream", "value"))
        })
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.SetCookie.name).isEmpty,
      )
    },
    test("forwards non-EDGE_SESSION cookies upstream but removes EDGE_SESSION") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))
          .addCookie(Cookie.Request("other", "keep-me"))
        service = env.buildService(client, security)
        _ <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        upstream.exists(_.cookies.exists(c => c.name == "other" && c.content == "keep-me")),
        upstream.exists(_.cookies.forall(_.name != EdgeSessionCookie.name)),
      )
    },
    test("preserves original query params on the upstream URL") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        url = (URL.empty / "users").addQueryParam("page", "1").addQueryParam("sort", "name")
        request = Request.get(url).addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        _ <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        upstream.exists(_.url.queryParams.getAll("page") == Chunk("1")),
        upstream.exists(_.url.queryParams.getAll("sort") == Chunk("name")),
      )
    },
    test("refreshes expired session via refresh token and rotates EDGE_SESSION cookie") {
      val env = new Env
      val refreshTokenValue = "rt-secret"
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        _ <- env.withPresets(preset)
        _ <- env.withClients(oauthClient)
        expiredToken <- env.signToken(jti = "old-jti", ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        encryptionKey = SecretKeySpec(env.edgeConfig.security.tokenEncryption.key, "AES")
        encryptedRefresh <- security.encryptAes256(refreshTokenValue.getBytes("UTF-8"), encryptionKey)
        now <- Clock.instant
        record = session.EdgeRefreshTokenRecord(
          presetId = presetId,
          encryptedRefreshToken = Secret(encryptedRefresh),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.refreshTokenRepository.find.succeedsWith(Some(record))
        newAccessToken <- env.signToken(jti = "new-jti", ttlSeconds = 600L)
        newTokens = TokenResponse(
          accessToken = newAccessToken, tokenType = "Bearer", expiresIn = 600L,
          refreshToken = Some(RefreshToken("new-refresh")),
          refreshTokenExpiresIn = Some(7200L), scope = None, idToken = None,
        )
        _ <- env.ssoClient.exchangeRefreshToken.succeedsWith(newTokens)
        _ <- env.refreshTokenRepository.create.succeedsWith(())
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get
        setCookieHeader = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).exists(_.renderedValue == "Bearer " + newAccessToken)),
        setCookieHeader.exists(c => c.name == EdgeSessionCookie.name && c.content == newAccessToken),
        env.ssoClient.exchangeRefreshToken.calls.headOption.exists(_._1 == RefreshToken(refreshTokenValue)),
        env.refreshTokenRepository.create.calls.size == 1,
      )
    },
    test("returns 303 SeeOther with cleared EDGE_SESSION cookie when refresh fails with invalid_grant") {
      val env = new Env
      val authorizeUrl = URL.decode("https://idp.example/authorize?client_id=web-app").toOption.get
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        _ <- env.withPresets(preset)
        _ <- env.withClients(oauthClient)
        expiredToken <- env.signToken(jti = "old-jti", ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        encryptionKey = SecretKeySpec(env.edgeConfig.security.tokenEncryption.key, "AES")
        encryptedRefresh <- security.encryptAes256("rt-secret".getBytes("UTF-8"), encryptionKey)
        now <- Clock.instant
        record = session.EdgeRefreshTokenRecord(
          presetId = presetId,
          encryptedRefreshToken = Secret(encryptedRefresh),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.refreshTokenRepository.find.succeedsWith(Some(record))
        _ <- env.ssoClient.exchangeRefreshToken.failsWith(SSOClient.InvalidGrant)
        _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
          case 1 => ZIO.succeed(Array.fill[Byte](32)(7))
          case 2 => ZIO.succeed(Array.fill[Byte](16)(9))
          case _ => ZIO.succeed(Array.fill[Byte](32)(11))
        _ <- env.loginRepository.create.succeedsWith(())
        _ <- env.ssoClient.authorizeUri.succeedsWith(authorizeUrl)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        setCookieHeader = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url == authorizeUrl),
        setCookieHeader.exists(c => c.name == EdgeSessionCookie.name && c.content.isEmpty),
        setCookieHeader.flatMap(_.maxAge).contains(Duration.Zero),
      )
    },
    test("exposes user info in CEL allow and inject rules") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("user.email == 'john@example.com'"),
        inject = Vector(
          InjectRule(
            InjectTarget.header, "x-user-email", "user.email")
        ),
        fetchUserInfo = true,
      )
      for
        _ <- env.setupDefaults()
        _ <- env.ssoClient.userInfo.succeedsWith(Json.Obj("email" -> Json.Str("john@example.com")))
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy("users-api", Path.decode("/users"), request)
        upstream <- capture.get

      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-user-email").contains("john@example.com")),
        env.ssoClient.userInfo.calls.nonEmpty,
      )
    },
    test("returns 401 when userInfo is unauthorized") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("user.email == 'john@example.com'"),
        fetchUserInfo = true,
      )

      for
        _ <- env.setupDefaults()
        _ <- env.ssoClient.userInfo.failsWith(SSOClient.UserInfoUnauthorized)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)

        response <- service.proxy(
          "users-api",
          Path.decode("/users"),
          request,
        )

      yield assertTrue(
        response.status == Status.Unauthorized,
      )
    },
    test("returns 500 when userInfo request fails") {
      val env = new Env
      val endpoint = usersEndpoint(
        fetchUserInfo = true,
      )

      for
        _ <- env.setupDefaults()
        _ <- env.ssoClient.userInfo.failsWith(
          new RuntimeException("userinfo failed")
        )

        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]

        _ <- env.withResources(usersResource(endpoint))

        token <- env.signToken()

        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))

        service = env.buildService(client, security)

        response <- service.proxy(
          "users-api",
          Path.decode("/users"),
          request,
        )

      yield assertTrue(
        response.status == Status.InternalServerError,
      )
    }
  )
end EdgeServiceProxySpec
