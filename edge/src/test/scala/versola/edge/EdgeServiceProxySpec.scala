package versola.edge

import com.nimbusds.jose.crypto.{ECDSASigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.ZIOStubs
import versola.edge.dpop.{DpopReplayGuard, DpopVerifier}
import versola.edge.login.LoginRepository
import versola.edge.model.*
import versola.edge.revocation.{RevocationKey, TokenRevocationService}
import versola.util.cel.CelEvaluator
import versola.util.http.Observability
import versola.util.{EnvName, JWT, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPublicKey}
import java.util.{Date, UUID}
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.SeqHasAsJava

object EdgeServiceProxySpec extends ZIOSpecDefault, ZIOStubs:

  private val presetId = PresetId("preset-default")
  private val clientId = ClientId("web-app")
  private val backendUrl = URL.decode("http://backend.local").toOption.get
  private val centralUrl = URL.decode("https://central.example").toOption.get
  /** The origin clients reach the edge on, and so the base of every `htu` a proof is
    * checked against. Deliberately neither the upstream's nor auth's. */
  private val edgePublicUrl = URL.decode("https://edge.example").toOption.get
  private val centralClientId = ClientId("central-admin")

  private val preset = AuthorizationPreset(
    id = presetId,
    clientId = clientId,
    description = "default",
    redirectUri = versola.util.RedirectUri("https://app.example/complete"),
    postLoginRedirectUri = versola.util.RedirectUri("https://app.example/home"),
    postLogoutRedirectUri = None,
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = Some("app.example"),
    cookiePath = Some("/"),
  )

  private val oauthClient = OAuthClient(id = clientId, secret = Secret(Array.fill(48)(1.toByte)), permissions = Set.empty, accessTokenTtl = 15.minutes)
  private val svcClient = OAuthClient(id = ClientId("svc-1"), secret = Secret(Array.fill(48)(3.toByte)), permissions = Set.empty, accessTokenTtl = 15.minutes)

  class Env:
    val secureRandom = stub[SecureRandom]
    val loginRepository = stub[LoginRepository]
    val ssoClient = stub[SSOClient]
    val jwksService = stub[JwksService]
    val sessionRepository = stub[session.EdgeSessionRepository]
    val revocationService = stub[TokenRevocationService]
    val permissionService = stub[PermissionService]

    val resourceCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ResourceId, Resource])))
    val presetCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset])))
    val clientCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ClientId, OAuthClient])))
    val resourceService = ResourceService.Impl(resourceCache, stub[ResourcesSyncClient])
    val clientService = OAuthClientService.Impl(presetCache, clientCache, stub[AuthorizationPresetsSyncClient], stub[OAuthClientsSyncClient])
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
      configurationCacheRefreshInterval = 5.minutes,
      dpop = Some(
        EdgeConfig.Dpop(
          publicUrl = edgePublicUrl,
          nonceSalt = Secret.Bytes32(Array.fill(32)(7.toByte)),
        ),
      ),
    )

    val replayGuard = DpopReplayGuard.Impl()
    val dpopVerifier: DpopVerifier = DpopVerifier.Impl(edgeConfig, replayGuard)

    val publicKeys: JWT.PublicKeys =
      val rsaKey = RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(edgeConfig.keyId).build()
      JWT.PublicKeys(JWKSet(rsaKey))

    def signToken(
        sub: String = "user-1",
        role: String = "admin",
        ttlSeconds: Long = 600,
        clientId: String = "web-app",
        tenantId: String = "default",
        roles: List[String] = Nil,
        jti: String = UUID.randomUUID().toString,
        acr: Option[String] = None,
        authTime: Option[Long] = None,
        sid: String = "sso-session-1",
        audience: List[String] = List(backendUrl.encode),
        cnfJkt: Option[String] = None,
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
            .audience(audience.asJava)
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("client_id", clientId)
            .claim("role", role)
            .claim("sid", sid)
            .claim("tenant_id", tenantId)
          cnfJkt.foreach(v => builder.claim("cnf", java.util.Map.of("jkt", v)))
          acr.foreach(v => builder.claim("acr", v))
          authTime.foreach(v => builder.claim("auth_time", v))
          val javaRoles = new java.util.ArrayList[String]()
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
        _ <- permissionService.getAllowedEndpointsForRoles.succeedsWith(scenarioEndpointIds)
        _ <- permissionService.getAllowedEndpointsForClient.succeedsWith(scenarioEndpointIds)
        _ <- sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- revocationService.isRevoked.succeedsWith(false)
        _ <- withClients(oauthClient)
      yield ()

    def withResources(values: Resource*): UIO[Unit] =
      resourceCache.set(values.map(r => r.resourceId -> r).toMap)

    def withPresets(values: AuthorizationPreset*): UIO[Unit] =
      presetCache.set(values.map(p => p.id -> p).toMap)

    def withClients(values: OAuthClient*): UIO[Unit] =
      clientCache.set(values.map(c => c.id -> c).toMap)

    def buildService(
        httpClient: Client,
        security: SecurityService,
        verifier: DpopVerifier = dpopVerifier,
    ): EdgeService =
      EdgeService.Impl(
        clientService,
        resourceService,
        celEvaluator,
        secureRandom,
        loginRepository,
        ssoClient,
        security,
        httpClient,
        edgeConfig,
        sessionRepository,
        revocationService,
        jwksService,
        permissionService,
        verifier,
        EnvName.Test("test"),
      )

  private val securityServiceLayer: ULayer[SecurityService] =
    SecureRandom.live >>> SecurityService.live

  def spec = suite("EdgeService.proxy")(
    proxySuite,
  ).provideLayer(TestClient.layer ++ securityServiceLayer) @@ TestAspect.silentLogging

  private val usersEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000401"))
  private val userByIdEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000402"))
  private val createUserEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000403"))
  private val currentUserEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000404"))
  private val tenantOrderEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000405"))

  /** Endpoints granted by `setupDefaults` so proxy-behaviour scenarios are not gated by deny-by-default authorization. */
  private val scenarioEndpointIds: Set[ResourceEndpointId] =
    Set(usersEndpointId, userByIdEndpointId, createUserEndpointId, currentUserEndpointId, tenantOrderEndpointId)

  private def usersEndpoint(
      allow: Option[String] = None,
      inject: Vector[InjectRule] = Vector.empty,
      fetchUserInfo: Boolean = false,
      stepUpCondition: Option[String] = None,
      stepUpAcr: Option[String] = None,
      maxAge: Option[Int] = None,
  ) =
    ResourceEndpoint(
      id = usersEndpointId,
      method = "GET",
      path = "/users",
      fetchUserInfo = fetchUserInfo,
      allow = allow,
      inject = inject,
      stepUpCondition = stepUpCondition,
      stepUpAcr = stepUpAcr,
      maxAge = maxAge,
    )

  private def userByIdEndpoint(allow: Option[String] = None, inject: Vector[InjectRule] = Vector.empty) =
    ResourceEndpoint(
      id = userByIdEndpointId,
      method = "GET",
      path = "/users/{id}",
      fetchUserInfo = false,
      allow = allow,
      inject = inject,
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
    )

  private def currentUserEndpoint(inject: Vector[InjectRule] = Vector.empty) =
    ResourceEndpoint(
      id = currentUserEndpointId,
      method = "GET",
      path = "/users/me",
      fetchUserInfo = false,
      allow = None,
      inject = inject,
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
    )

  private def tenantOrderEndpoint(allow: Option[String] = None, inject: Vector[InjectRule] = Vector.empty) =
    ResourceEndpoint(
      id = tenantOrderEndpointId,
      method = "GET",
      path = "/tenants/{tenantId}/orders/{orderId}",
      fetchUserInfo = false,
      allow = allow,
      inject = inject,
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
    )

  private def createUserEndpoint(
      allow: Option[String] = None,
      inject: Vector[InjectRule] = Vector.empty,
      stepUpCondition: Option[String] = None,
      stepUpAcr: Option[String] = None,
  ) =
    ResourceEndpoint(
      id = createUserEndpointId,
      method = "POST",
      path = "/users",
      fetchUserInfo = false,
      allow = allow,
      inject = inject,
      stepUpCondition = stepUpCondition,
      stepUpAcr = stepUpAcr,
      maxAge = None,
    )

  private def usersResource(endpoints: ResourceEndpoint*) =
    Resource(resourceId = ResourceId("users-api"), resource = backendUrl, endpoints = endpoints.toVector, secret = None)

  private def centralEndpoint() =
    ResourceEndpoint(
      id = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000501")),
      method = "GET",
      path = "/tenants",
      fetchUserInfo = false,
      allow = None,
      inject = Vector.empty,
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
    )

  private val centralResourceSecret = Secret(Array.fill(48)(2.toByte))

  /** The central-facing resource is internal: it always carries a secret, so edge
    * authenticates to it with `Basic(resourceId, secret)` instead of forwarding
    * the caller's own token (see [[EdgeService.Impl.resolveAuthHeader]]).
    */
  private def centralResource(endpoints: ResourceEndpoint*) =
    Resource(resourceId = ResourceId("central"), resource = centralUrl, endpoints = endpoints.toVector, secret = Some(centralResourceSecret))

  private val accountResourceSecret = Secret(Array.fill(48)(4.toByte))

  private def accountEndpoint() =
    ResourceEndpoint(
      id = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000601")),
      method = "GET",
      path = "/settings/passkeys",
      fetchUserInfo = false,
      allow = None,
      inject = Vector(InjectRule(InjectTarget.query, "userId", "token.sub")),
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
    )

  /** The "auth" alias proxies Account Settings to auth's additional listener. Like "central" it is
    * internal: edge authenticates with `Basic(resourceId, secret)` rather than forwarding the caller's token.
    */
  private def accountResource(endpoints: ResourceEndpoint*) =
    Resource(resourceId = ResourceId("auth"), resource = backendUrl, endpoints = endpoints.toVector, secret = Some(accountResourceSecret))

  private def captureUpstream(status: Status = Status.Ok, body: String = "ok"): ZIO[TestClient, Nothing, Ref[Option[Request]]] =
    for
      capture <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoute(Method.ANY / trailing -> handler { (req: Request) =>
        capture.set(Some(req)).as(Response(status = status, body = Body.fromString(body)))
      })
    yield capture

  private def sessionCookie(value: String): Cookie.Request =
    Cookie.Request(EdgeSessionCookie.name, s"${presetId}:$value")

  private val dpopKeyPair =
    val generator = KeyPairGenerator.getInstance("EC").nn
    generator.initialize(Curve.P_256.toECParameterSpec)
    generator.generateKeyPair().nn
  private val dpopJwk =
    ECKey.Builder(Curve.P_256, dpopKeyPair.getPublic.asInstanceOf[ECPublicKey]).build()
  private val dpopJkt = dpopJwk.computeThumbprint().toString

  /** The proof a DPoP client sends alongside its token: signed over this edge's public
    * origin and the path the client called, and naming the token via `ath`. */
  private def dpopProof(
      accessToken: String,
      path: String,
      method: Method = Method.GET,
      jti: String = "proof-1",
      htu: Option[String] = None,
  ): Task[String] =
    Clock.instant.flatMap: now =>
      ZIO.attemptBlocking:
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
          .`type`(versola.util.Dpop.JwtType)
          .jwk(dpopJwk)
          .build()
        val ath = versola.util.Base64.urlEncode(
          java.security.MessageDigest.getInstance("SHA-256").nn
            .digest(accessToken.getBytes("US-ASCII")).nn,
        )
        val claims = JWTClaimsSet.Builder()
          .claim("htm", method.name)
          .claim("htu", htu.getOrElse(s"${edgePublicUrl.encode}$path"))
          .claim("ath", ath)
          .jwtID(jti)
          .issueTime(Date.from(now))
          .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(dpopKeyPair.getPrivate.asInstanceOf[ECPrivateKey]))
        jwt.serialize()

  private def dpopRequest(path: String, token: AccessToken, proof: String): Request =
    Request.get(URL.decode(path).toOption.get)
      .addHeader(Header.Custom("Authorization", s"DPoP $token"))
      .addHeader(Header.Custom("DPoP", proof))

  private val proxySuite = suite("scenarios")(
    test("returns 401 when EDGE_SESSION cookie is missing") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), Request.get(URL.empty / "users"))
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("returns 401 for a signed, unexpired token whose jti has been revoked") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- env.revocationService.isRevoked.succeedsWith(true)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(jti = "revoked-jti", sid = "sso-session-1")
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        // Both the token and its session are offered, so a session-wide logout is caught
        // by the same lookup as a single revoked token.
        env.revocationService.isRevoked.calls.map(_._1) ==
          List(List(
            RevocationKey.Jti(AccessTokenId("revoked-jti")),
            RevocationKey.Sid(SessionId("sso-session-1")),
            RevocationKey.Sub("user-1"),
          )),
      )
    },
    test("rejects a bearer token of a logged-out session even with no edge_sessions row") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        // The bearer path never consults edge_sessions, so only the `sid` entry can stop it.
        _ <- env.revocationService.isRevoked.returnsZIO((keys, _) => ZIO.succeed(keys.contains(RevocationKey.Sid(SessionId("sso-session-1")))))
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(sid = "sso-session-1")
        request = Request.get(URL.empty / "users").addHeader(Header.Authorization.Bearer(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        env.sessionRepository.findByAccessTokenId.calls.isEmpty,
      )
    },
    test("returns 401 for a token of a user an administrator has revoked") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        // Neither the token nor its session is named by the revocation — only the user is,
        // which is the whole point of an administrator ending their access.
        _ <- env.revocationService.isRevoked.returnsZIO((keys, _) => ZIO.succeed(keys.contains(RevocationKey.Sub("user-1"))))
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(jti = "untouched-jti", sid = "sso-session-2")
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("checks a token against the revocation list by its own issue time, not by now") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- captureUpstream()
        // A user-wide entry only covers tokens that predate it, so which token is spared
        // turns entirely on the `iat` the check is given. Passing `now` instead would make
        // every entry cover every token, locking a user out until it expired.
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        _ <- TestClock.adjust(600.seconds)
        issued <- Clock.instant
        token <- env.signToken(jti = "fresh-jti", sid = "sso-session-3")
        _ <- TestClock.adjust(60.seconds)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Ok,
        env.revocationService.isRevoked.calls.map(_._1) ==
          List(List(
            RevocationKey.Jti(AccessTokenId("fresh-jti")),
            RevocationKey.Sid(SessionId("sso-session-3")),
            RevocationKey.Sub("user-1"),
          )),
        env.revocationService.isRevoked.calls.map(_._2) == List(issued),
      )
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
        response <- service.proxy(ResourceId("ghost"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("returns 403 when an allow expression reads a claim the token doesn't carry") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint(allow = Some("token.subscription == 'premium'"))))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.Forbidden, upstream.isEmpty)
    },
    test("returns 500 when an allow expression cannot be evaluated at all") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint(allow = Some("1 / 0 == 0"))))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.InternalServerError, upstream.isEmpty)
    },
    test("returns 403 when public resource URI is absent from token audience") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(audience = List("https://another.example"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("rejects the legacy Edge resource audience format") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(audience = List(s"${env.edgeConfig.versolaUrl.encode}/resources/users-api"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("allows a public resource when its audience is mixed with Edge") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(audience =
          List(
            backendUrl.encode,
            "resource://edge",
          ),
        )
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("forwards bearer token to a public resource when client is absent from the secret cache") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("token.role == 'admin'"),
        inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub")),
      )
      for
        _ <- env.setupDefaults()
        _ <- env.withClients()
        capture <- captureUpstream(body = "users-payload")
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addHeader(Header.Custom("x-user", "attacker-controlled"))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        body <- response.body.asString
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        body == "users-payload",
        upstream.exists(_.header(Header.Authorization).contains(Header.Authorization.Bearer(token))),
        upstream.exists(_.headers.get("x-user").contains("user-1")),
        upstream.exists(_.headers.get(Header.Cookie.name).isEmpty),
      )
    },
    test("refuses the request when an injected header's CEL expression reads an absent claim") {
      val env = new Env
      val endpoint = usersEndpoint(
        inject = Vector(InjectRule(InjectTarget.header, "x-optional", "token.missing")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addHeader(Header.Custom("x-optional", "attacker-controlled"))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Forbidden,
        upstream.isEmpty,
      )
    },
    test("injects an empty value when a has()-guarded expression finds no claim") {
      val env = new Env
      val endpoint = usersEndpoint(
        inject = Vector(InjectRule(InjectTarget.header, "x-optional", "has(token.missing) ? token.missing : ''")),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users")
          .addHeader(Header.Custom("x-optional", "attacker-controlled"))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-optional").contains("")),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("returns 401 with acr_values challenge when token acr is below endpoint requirement") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("otp-level"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""error="insufficient_user_authentication"""")),
        challenge.exists(_.contains("""acr_values="passkey-level"""")),
      )
    },
    test("requires step-up when the condition reads a claim the token doesn't carry") {
      val env = new Env
      val endpoint = usersEndpoint(
        stepUpCondition = Some("token.risk_score > 50"),
        stepUpAcr = Some("passkey-level"),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("otp-level"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""acr_values="passkey-level"""")),
        upstream.isEmpty,
      )
    },
    test("returns 500 when the step-up condition cannot be evaluated at all") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("1 / 0 == 0"), stepUpAcr = Some("passkey-level"))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("passkey-level"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.InternalServerError,
        upstream.isEmpty,
      )
    },
    test("forwards upstream when token acr satisfies endpoint requirement") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("passkey-level"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("forwards upstream when token acr matches any value in a multi-value requirement") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level mfa-level"))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("mfa-level"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("returns 401 when token acr does not match any value in a multi-value requirement") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level mfa-level"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("password-only"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""error="insufficient_user_authentication"""")),
        challenge.exists(_.contains("""acr_values="passkey-level mfa-level"""")),
      )
    },
    test("returns 401 with max_age challenge when auth_time is older than endpoint maxAge") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(authTime = Some(now.getEpochSecond - 600))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""error="insufficient_user_authentication"""")),
        challenge.exists(_.contains("""max_age="300"""")),
      )
    },
    test("returns 401 with max_age challenge when auth_time claim is absent") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.headers.get("WWW-Authenticate").exists(_.contains("""max_age="300"""")),
      )
    },
    test("forwards upstream when auth_time is within endpoint maxAge") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(authTime = Some(now.getEpochSecond - 60))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("forwards upstream when auth_time equals maxAge boundary exactly") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(authTime = Some(now.getEpochSecond - 300))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("returns 401 with acr_values challenge when token has no acr claim") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken() // no acr claim
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""acr_values="passkey-level"""")),
      )
    },
    test("returns 401 with both acr_values and max_age in challenge when both requirements fail") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"), maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("password-only"), authTime = Some(now.getEpochSecond - 600))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""acr_values="passkey-level"""")),
        challenge.exists(_.contains("""max_age="300"""")),
      )
    },
    test("challenge includes only acr_values when only ACR fails and max_age is satisfied") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"), maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("password-only"), authTime = Some(now.getEpochSecond - 60))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""acr_values="passkey-level"""")),
        challenge.forall(!_.contains("max_age")),
      )
    },
    test("challenge includes only max_age when only max_age fails and ACR is satisfied") {
      val env = new Env
      val endpoint = usersEndpoint(stepUpCondition = Some("true"), stepUpAcr = Some("passkey-level"), maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("passkey-level"), authTime = Some(now.getEpochSecond - 600))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""max_age="300"""")),
        challenge.forall(!_.contains("acr_values")),
      )
    },
    test("enforces max_age independently when no stepUpCondition or stepUpAcr is set") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(authTime = Some(now.getEpochSecond - 600))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""max_age="300"""")),
        challenge.forall(!_.contains("acr_values")),
      )
    },
    test("passes when max_age is set with no stepUpCondition and token is fresh enough") {
      val env = new Env
      val endpoint = usersEndpoint(maxAge = Some(300))
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        now <- Clock.instant
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(authTime = Some(now.getEpochSecond - 60))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("returns 401 with acr_values challenge when stepUpCondition is met and token has lower acr") {
      val env = new Env
      val endpoint = createUserEndpoint(
        stepUpCondition = Some("request.body.amount > 1000"),
        stepUpAcr = Some("mfa"),
      )
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("password"))
        body = Json.Obj("amount" -> Json.Num(1500)).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        challenge = response.headers.get("WWW-Authenticate")
      yield assertTrue(
        response.status == Status.Unauthorized,
        challenge.exists(_.contains("""error="insufficient_user_authentication"""")),
        challenge.exists(_.contains("""acr_values="mfa"""")),
      )
    },
    test("forwards request when stepUpCondition is met and token satisfies it") {
      val env = new Env
      val endpoint = createUserEndpoint(
        stepUpCondition = Some("request.body.amount > 1000"),
        stepUpAcr = Some("mfa"),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("mfa"))
        body = Json.Obj("amount" -> Json.Num(1500)).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("forwards request when stepUpCondition is not met") {
      val env = new Env
      val endpoint = createUserEndpoint(
        stepUpCondition = Some("request.body.amount > 1000"),
        stepUpAcr = Some("mfa"),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(acr = Some("password"))
        body = Json.Obj("amount" -> Json.Num(500)).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Ok)
    },
    test("matches parameterized path and exposes path parameters in CEL context") {
      val env = new Env
      val endpoint = userByIdEndpoint(
        allow = Some("request.path.params.id == token.sub"),
        inject = Vector(InjectRule(InjectTarget.header, "x-resource-id", "request.path.params.id")),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users/user-1"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-resource-id").contains("user-1")),
      )
    },
    test("exposes every parameter of a multi-segment template under request.path.params") {
      val env = new Env
      val endpoint = tenantOrderEndpoint(
        allow = Some("request.path.params.tenantId == 'acme' && request.path.params.orderId == '123'"),
        inject = Vector(
          InjectRule(InjectTarget.header, "x-tenant", "request.path.params.tenantId"),
          InjectRule(InjectTarget.header, "x-order", "request.path.params.orderId"),
        ),
      )
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "tenants" / "acme" / "orders" / "123").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/tenants/acme/orders/123"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.headers.get("x-tenant").contains("acme")),
        upstream.exists(_.headers.get("x-order").contains("123")),
      )
    },
    test("reports the registered template, not the concrete path, as the metric route") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(tenantOrderEndpoint()))
        token <- env.signToken()
        request = Request.get(URL.empty / "tenants" / "acme" / "orders" / "123").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/tenants/acme/orders/123"), request)
        routePath <- Observability.routePath.get
      yield assertTrue(
        response.status == Status.Ok,
        routePath.contains("/resources/users-api/tenants/{tenantId}/orders/{orderId}"),
      )
    },
    test("leaves the metric route at its default when no endpoint matches") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        request = Request.get(URL.empty / "ghosts" / "42").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        (response, routePath) <- Observability.routePath.locally(None):
          service.proxy(ResourceId("users-api"), Path.decode("/ghosts/42"), request) <*> Observability.routePath.get
      yield assertTrue(
        response.status == Status.NotFound,
        routePath.isEmpty,
      )
    },
    test("prefers a static endpoint over a parameterized one whatever the registration order") {
      val env = new Env
      val parameterized = userByIdEndpoint(inject = Vector(InjectRule(InjectTarget.header, "x-matched", "'by-id'")))
      val static = currentUserEndpoint(inject = Vector(InjectRule(InjectTarget.header, "x-matched", "'me'")))
      def matchedHeader(endpoints: ResourceEndpoint*) =
        for
          capture <- captureUpstream()
          client <- ZIO.service[Client]
          security <- ZIO.service[SecurityService]
          _ <- env.withResources(usersResource(endpoints*))
          token <- env.signToken()
          request = Request.get(URL.empty / "users" / "me").addCookie(sessionCookie(token))
          service = env.buildService(client, security)
          _ <- service.proxy(ResourceId("users-api"), Path.decode("/users/me"), request)
          upstream <- capture.get
        yield upstream.flatMap(_.headers.get("x-matched"))
      for
        _ <- env.setupDefaults()
        staticFirst <- matchedHeader(static, parameterized)
        parameterizedFirst <- matchedHeader(parameterized, static)
      yield assertTrue(
        staticFirst.contains("me"),
        parameterizedFirst.contains("me"),
      )
    },
    test("returns 403 when path parameter does not satisfy allow expression") {
      val env = new Env
      val endpoint = userByIdEndpoint(allow = Some("request.path.params.id == token.sub"))
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken()
        request = Request.get(URL.empty / "users" / "intruder").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users/intruder"), request)
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
        body = Json.Obj(
          "action" -> Json.Str("create"),
          "name" -> Json.Str("Alice"),
          "actor" -> Json.Str("attacker-controlled"),
        ).toJson
        request = Request.post(URL.empty / "users", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
        forwarded <- ZIO.foreach(upstream)(_.body.asString).map(_.getOrElse(""))
        parsed = forwarded.fromJson[Json.Obj].toOption
      yield assertTrue(
        response.status == Status.Ok,
        parsed.exists(_.fields.exists((k, v) => k == "actor" && v == Json.Str("user-1"))),
        parsed.exists(_.fields.count(_._1 == "actor") == 1),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).contains(Header.Authorization.Bearer(token))),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        env.sessionRepository.findByAccessTokenId.calls.isEmpty,
      )
    },
    // Distinct from the expired-token case above: a token that fails to parse or verify at
    // all (JWT.Error other than Expired) takes the catch-all `case _` branch, which never
    // attempts a refresh even over a cookie -- there is no accessTokenId to look up by.
    test("returns 401 when bearer token is not a valid JWT at all") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        request = Request.get(URL.empty / "users").addHeader(Header.Authorization.Bearer("not-a-jwt"))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        env.sessionRepository.findByAccessTokenId.calls.isEmpty,
      )
    },
    test("forwards request when user role grants access to the endpoint") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        _ <- env.withClients(oauthClient)
        token <- env.signToken(tenantId = "default", roles = List("editor"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.isDefined,
        env.permissionService.getAllowedEndpointsForRoles.calls == List((TenantId.default, List(RoleId("editor")))),
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
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(tenantId = "default", roles = List("guest"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("non-central resource is deny-by-default: returns 403 when role allowed set is empty") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(tenantId = "default", roles = List("guest"))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("uses client permissions when access token is a service token (sub == client_id)") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set(endpoint.id))
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        _ <- env.withClients(svcClient)
        token <- env.signToken(sub = "svc-1", clientId = "svc-1")
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.isDefined,
        env.permissionService.getAllowedEndpointsForClient.calls == List(ClientId("svc-1")),
        env.permissionService.getAllowedEndpointsForRoles.calls.isEmpty,
      )
    },
    test("service token is deny-by-default: returns 403 when client allowed set is empty") {
      val env = new Env
      val endpoint = usersEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(endpoint))
        token <- env.signToken(sub = "svc-1", clientId = "svc-1")
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Forbidden,
        env.permissionService.getAllowedEndpointsForClient.calls == List(ClientId("svc-1")),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.url.queryParams.getAll("actor") == Chunk("user-1")),
      )
    },
    // Mirrors "returns 500 when an allow expression cannot be evaluated at all", but for the
    // inject-rule evaluator: a rule that cannot be evaluated is a configuration error, so
    // it fails the whole request with 500 rather than silently omitting the header.
    test("returns 500 when an inject rule cannot be evaluated at all") {
      val env = new Env
      val endpoint = usersEndpoint(
        inject = Vector(InjectRule(InjectTarget.header, "X-Broken", "1 / 0 == 0")),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.InternalServerError, upstream.isEmpty)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        _ <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        _ <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = Some(Secret(encryptedRefresh)),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        newAccessToken <- env.signToken(jti = "new-jti", ttlSeconds = 600L)
        newTokens = TokenResponse(
          accessToken = newAccessToken,
          tokenType = "Bearer",
          expiresIn = 600L,
          refreshToken = Some(RefreshToken("new-refresh")),
          refreshTokenExpiresIn = Some(7200L),
          scope = None,
          idToken = None,
        )
        _ <- env.ssoClient.exchangeRefreshToken.succeedsWith(newTokens)
        _ <- env.sessionRepository.create.succeedsWith(())
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
        setCookieHeader = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).contains(Header.Authorization.Bearer(newAccessToken))),
        setCookieHeader.exists(c => c.name == EdgeSessionCookie.name && c.content == s"${presetId}:${newAccessToken}"),
        env.ssoClient.exchangeRefreshToken.calls.headOption.exists(_._1 == RefreshToken(refreshTokenValue)),
        env.sessionRepository.create.calls.size == 1,
      )
    },
    test("returns 401 with Location /login/<preset> and cleared cookie when refresh fails with invalid_grant") {
      val env = new Env
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
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = Some(Secret(encryptedRefresh)),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        _ <- env.ssoClient.exchangeRefreshToken.failsWith(SSOClient.InvalidGrant)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        setCookieHeader = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.header(Header.Location).exists(_.url.encode == s"/login/${presetId}"),
        setCookieHeader.exists(c => c.name == EdgeSessionCookie.name && c.content.isEmpty),
        setCookieHeader.flatMap(_.maxAge).contains(Duration.Zero),
      )
    },
    test("returns 401 with Location /login/<preset> from cookie when no refresh token record exists") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        expiredToken <- env.signToken(jti = "old-jti", ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        setCookieHeader = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.header(Header.Location).exists(_.url.encode == s"/login/${presetId}"),
        setCookieHeader.exists(c => c.name == EdgeSessionCookie.name && c.content.isEmpty),
      )
    },
    // The preset lookup uses cookiePresetId (from the cookie itself), not the record's own
    // presetId, and clears the cookie with no domain/path since there is no preset left to
    // supply them.
    test("returns 401 with Location /login/<preset> when the session's preset has been removed") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        expiredToken <- env.signToken(jti = "old-jti", ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        now <- Clock.instant
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = Some(Secret(Array.fill(16)(1.toByte))),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        // No env.withPresets(preset): clientService.findPreset(record.presetId) misses.
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.header(Header.Location).exists(_.url.encode == s"/login/${presetId}"),
        response.header(Header.SetCookie).map(_.value) == Some(EdgeSessionCookie.clear(None, None)),
      )
    },
    // Distinct from the "no refresh token record exists" case above: here the session row and
    // its preset are both found, but the row itself never received a refresh token. The
    // fallback cookie then carries the preset's own domain/path, not None/None.
    test("returns 401 with the preset's cookie domain/path when the session record has no refresh token") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        _ <- env.withPresets(preset)
        expiredToken <- env.signToken(jti = "old-jti", ttlSeconds = 1L)
        _ <- TestClock.adjust(2.seconds)
        now <- Clock.instant
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = None,
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.header(Header.Location).exists(_.url.encode == s"/login/${presetId}"),
        response.header(Header.SetCookie).map(_.value) ==
          Some(EdgeSessionCookie.clear(preset.cookieDomain, preset.cookiePath)),
      )
    },
    // `rotate` looks the current key set up twice (once via storeSession, once to build the
    // new cookie's claims): if it changed in between -- e.g. a JWKS reload dropped the
    // signing key -- the freshly-minted token from the OP itself can fail the second check.
    test("returns 401 when the rotated access token fails verification against a key set that changed mid-refresh") {
      val env = new Env
      val refreshTokenValue = "rt-secret"
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
        encryptedRefresh <- security.encryptAes256(refreshTokenValue.getBytes("UTF-8"), encryptionKey)
        now <- Clock.instant
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = Some(Secret(encryptedRefresh)),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        newAccessToken <- env.signToken(jti = "new-jti", ttlSeconds = 600L)
        newTokens = TokenResponse(
          accessToken = newAccessToken,
          tokenType = "Bearer",
          expiresIn = 600L,
          refreshToken = None,
          refreshTokenExpiresIn = None,
          scope = None,
          idToken = None,
        )
        _ <- env.ssoClient.exchangeRefreshToken.succeedsWith(newTokens)
        _ <- env.sessionRepository.create.succeedsWith(())
        otherKeyPair = {
          val gen = KeyPairGenerator.getInstance("RSA").nn
          gen.initialize(2048)
          gen.generateKeyPair().nn
        }
        // Same key id as the real signing key, but a different key pair: the rotated token's
        // signature was made with the real key, so verifying it against this one fails.
        mismatchedKeys = JWT.PublicKeys(JWKSet(
          RSAKey.Builder(otherKeyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(env.edgeConfig.keyId).build(),
        ))
        _ <- env.jwksService.getPublicKeys.returnsZIOOnCall:
          case 1 => ZIO.succeed(env.publicKeys) // proxyInternal validating the incoming expired token
          case 2 => ZIO.succeed(env.publicKeys) // storeSession/extractTokenIds validating the rotated token
          case _ => ZIO.succeed(mismatchedKeys) // rotate's own check right after, key set has since changed
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
      yield assertTrue(response.status == Status.Unauthorized)
    },
    // Any Throwable that isn't SSOClient.InvalidGrant or an Outcome propagates unchanged,
    // first out of `rotate`'s own catchAll, then out of `proxy`'s -- covering both re-raises.
    test("propagates a generic Throwable from a failed token refresh") {
      val env = new Env
      val failure = RuntimeException("sso token endpoint unreachable")
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
        record = session.EdgeSessionRecord(
          publicSessionId = SessionId("sso-session-1"),
          presetId = presetId,
          accessTokenId = AccessTokenId("old-jti"),
          encryptedRefreshToken = Some(Secret(encryptedRefresh)),
          expiresAt = now.plusSeconds(3600),
        )
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(Some(record))
        _ <- env.ssoClient.exchangeRefreshToken.failsWith(failure)
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(expiredToken))
        service = env.buildService(client, security)
        result <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request).either
      yield assertTrue(result == Left(failure))
    },
    test("exposes user info in CEL allow and inject rules") {
      val env = new Env
      val endpoint = usersEndpoint(
        allow = Some("user.email == 'john@example.com'"),
        inject = Vector(
          InjectRule(
            InjectTarget.header,
            "x-user-email",
            "user.email",
          ),
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
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
          ResourceId("users-api"),
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
          new RuntimeException("userinfo failed"),
        )

        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]

        _ <- env.withResources(usersResource(endpoint))

        token <- env.signToken()

        request = Request.get(URL.empty / "users")
          .addCookie(sessionCookie(token))

        service = env.buildService(client, security)

        response <- service.proxy(
          ResourceId("users-api"),
          Path.decode("/users"),
          request,
        )
      yield assertTrue(
        response.status == Status.InternalServerError,
      )
    },
    test("central alias injects Basic auth header with resource secret instead of Bearer") {
      val env = new Env
      val endpoint = centralEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(clientId = "central-admin", audience = List("resource://edge"))
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).contains(
          Header.Authorization.Basic(ResourceId("central"), versola.util.Base64.urlEncode(centralResourceSecret)),
        )),
      )
    },
    test("returns 403 for an internal resource excluded by its audience") {
      val env = new Env
      val endpoint = centralEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(audience = List("resource://users-api"))
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("central alias is deny-by-default: returns 403 when endpoint not in allowed set") {
      val env = new Env
      val endpoint = centralEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken()
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("central alias: oauth-admin with matching endpoint permission is allowed") {
      val env = new Env
      val endpoint = centralEndpoint()
      val centralClient = OAuthClient(
        id = centralClientId,
        secret = Secret(Array.fill(48)(2.toByte)),
        permissions = Set.empty,
        accessTokenTtl = 15.minutes,
      )
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        _ <- env.withClients(centralClient)
        _ <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(
          clientId = "central-admin",
          tenantId = "default",
          roles = List("oauth-admin"),
          audience = List("resource://central"),
        )
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
      yield assertTrue(
        response.status == Status.Ok,
        env.permissionService.getAllowedEndpointsForRoles.calls ==
          List((TenantId.default, List(RoleId("oauth-admin")))),
      )
    },
    test("central alias: default-tenant roles are forwarded to permission check") {
      val env = new Env
      val endpoint = centralEndpoint()
      val centralClient = OAuthClient(
        id = centralClientId,
        secret = Secret(Array.fill(48)(2.toByte)),
        permissions = Set.empty,
        accessTokenTtl = 15.minutes,
      )
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        _ <- env.withClients(centralClient)
        _ <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(
          clientId = "central-admin",
          tenantId = "default",
          roles = List("editor"),
          audience = List(backendUrl.encode, "resource://central"),
        )
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
      yield assertTrue(
        response.status == Status.Ok,
        env.permissionService.getAllowedEndpointsForRoles.calls ==
          List((TenantId.default, List(RoleId("editor")))),
      )
    },
    test("central alias: no roles → 403") {
      val env = new Env
      val endpoint = centralEndpoint()
      val centralClient = OAuthClient(
        id = centralClientId,
        secret = Secret(Array.fill(48)(2.toByte)),
        permissions = Set.empty,
        accessTokenTtl = 15.minutes,
      )
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set.empty)
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(centralResource(endpoint))
        _ <- env.withClients(centralClient)
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(
          clientId = "central-admin",
          tenantId = "default",
          roles = Nil,
        )
        request = Request.get(URL.empty / "tenants").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("central"), Path.decode("/tenants"), request)
      yield assertTrue(
        response.status == Status.Forbidden,
        env.permissionService.getAllowedEndpointsForRoles.calls ==
          List((TenantId.default, List.empty)),
      )
    },
    test("account alias (Account Settings): authorized request is forwarded to auth's additional listener with Basic auth") {
      val env = new Env
      val endpoint = accountEndpoint()
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.permissionService.getAllowedEndpointsForRoles.succeedsWith(Set(endpoint.id))
        _ <- env.permissionService.getAllowedEndpointsForClient.succeedsWith(Set.empty)
        _ <- env.sessionRepository.findByAccessTokenId.succeedsWith(None)
        _ <- env.revocationService.isRevoked.succeedsWith(false)
        _ <- env.withResources(accountResource(endpoint))
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        token <- env.signToken(roles = List("user"), audience = List("resource://edge"))
        request = Request.get(URL.empty / "settings" / "passkeys").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("auth"), Path.decode("/settings/passkeys"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        upstream.exists(_.header(Header.Authorization).contains(
          Header.Authorization.Basic(ResourceId("auth"), versola.util.Base64.urlEncode(accountResourceSecret)),
        )),
        upstream.exists(_.url.queryParams.getAll("userId") == Chunk("user-1")),
      )
    },

    // ── RFC 9449 ──────────────────────────────────────────────────────────────
    test("forwards a DPoP-bound token's request when the proof checks out") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        proof <- dpopProof(token, "/users")
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Ok,
        // The proof is signed over this edge's own method and URI and is spent here, so
        // upstream has no use for it and never sees it.
        upstream.exists(_.rawHeader("DPoP").isEmpty),
      )
    },
    // The downgrade this exists to close: holding a key-bound token is not enough, whoever
    // presents it has to prove the key too.
    test("refuses a DPoP-bound token presented under the Bearer scheme") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        request = Request.get(URL.empty / "users").addHeader(Header.Authorization.Bearer(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.rawHeader("WWW-Authenticate").contains("""DPoP error="invalid_dpop_proof""""),
        upstream.isEmpty,
      )
    },
    // Same refusal over the session cookie, which carries a token and no proof at all.
    test("refuses a DPoP-bound token arriving in the session cookie") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        request = Request.get(URL.empty / "users").addCookie(sessionCookie(token))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.Unauthorized, upstream.isEmpty)
    },
    test("refuses a DPoP-scheme request whose proof is missing") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        request = Request.get(URL.empty / "users").addHeader(Header.Custom("Authorization", s"DPoP $token"))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.Unauthorized, upstream.isEmpty)
    },
    // The htu is rebuilt from the configured public URL, so a proof made over the address a
    // forwarding hop knows this edge by does not open the door.
    test("refuses a proof made over an origin other than the configured public URL") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        proof <- dpopProof(token, "/users", htu = Some("http://internal.local/users"))
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
        upstream <- capture.get
      yield assertTrue(response.status == Status.Unauthorized, upstream.isEmpty)
    },
    test("refuses a proof that has already been spent") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        _ <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        proof <- dpopProof(token, "/users")
        service = env.buildService(client, security)
        first <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
        replay <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
      yield assertTrue(first.status == Status.Ok, replay.status == Status.Unauthorized)
    },
    // A proof proves possession of some key; it says nothing about a token that was never
    // bound to one, so §7.1 has the token treated as invalid rather than as a bearer token.
    test("refuses the DPoP scheme for a token that carries no cnf claim") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken()
        proof <- dpopProof(token, "/users")
        service = env.buildService(client, security)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
        upstream <- capture.get
      yield assertTrue(response.status == Status.Unauthorized, upstream.isEmpty)
    },
    // A plain bearer token is unaffected by any of this, which is what keeps the browser
    // and the existing mobile clients working.
    test("leaves a token with no cnf claim to bearer semantics") {
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
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), request)
        upstream <- capture.get
      yield assertTrue(response.status == Status.Ok, upstream.isDefined)
    },
    // Turning the feature off must not turn the binding off with it.
    test("refuses a DPoP request when this edge has no dpop block configured") {
      val env = new Env
      for
        _ <- env.setupDefaults()
        capture <- captureUpstream()
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        _ <- env.withResources(usersResource(usersEndpoint()))
        token <- env.signToken(cnfJkt = Some(dpopJkt))
        proof <- dpopProof(token, "/users")
        unconfigured = DpopVerifier.Impl(env.edgeConfig.copy(dpop = None), DpopReplayGuard.Impl())
        service = env.buildService(client, security, unconfigured)
        response <- service.proxy(ResourceId("users-api"), Path.decode("/users"), dpopRequest("/users", token, proof))
        upstream <- capture.get
      yield assertTrue(response.status == Status.Unauthorized, upstream.isEmpty)
    },
  )
end EdgeServiceProxySpec
