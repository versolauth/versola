package versola.edge

import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.edge.model.{
  AccessToken,
  AuthConversationNotFound,
  ClientId,
  EdgeId,
  InvalidLogoutToken,
  PermissionId,
  PresetId,
  PresetNotFound,
  ResourceId,
  RoleId,
  SessionId,
  TenantId,
}
import versola.util.http.Observability
import versola.util.{JWT, RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import io.opentelemetry.api

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant

object EdgeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val edgeConfig = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val publicKeys: JWT.PublicKeys =
    val rsaKey = RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(edgeConfig.keyId).build()
    JWT.PublicKeys(JWKSet(rsaKey))

  private def token(
      clientId: String = "web-app",
      tenantId: Option[String] = None,
      roles: Option[List[String]] = None,
  ): Task[AccessToken] =
    val fields =
      Chunk(Some("client_id" -> Json.Str(clientId))) ++
        Chunk(tenantId.map(tid => "tenant_id" -> Json.Str(tid))) ++
        Chunk(roles.map(rs => "roles" -> Json.Arr(rs.map(Json.Str(_))*))
          .orElse(Some("roles" -> Json.Arr())))
    JWT.serialize(
      claims = JWT.Claims("auth", "user-1", List("edge"), Json.Obj(fields.flatten*)),
      ttl = 10.minutes,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeConfig.keyId, keyPair.getPrivate.nn),
      typ = JWT.Type.AccessToken,
    ).map(AccessToken(_))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def run(
      request: Request,
      setup: (Stub[EdgeService], Stub[JwksService]) => UIO[Unit] = (_, _) => ZIO.unit,
  ): ZIO[TestClient & Client & Scope, Throwable, (Response, Stub[EdgeService], Stub[JwksService])] =
    for
      client  <- ZIO.service[Client]
      service =  stub[EdgeService]
      jwks    =  stub[JwksService]
      presets =  stub[AuthorizationPresetsSyncClient]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          EdgeController.routes.provideEnvironment(
            ZEnvironment[EdgeService](service) ++
              ZEnvironment[JwksService](jwks) ++
              ZEnvironment[AuthorizationPresetsSyncClient](presets) ++
              ZEnvironment[EdgeConfig](edgeConfig) ++
              tracing,
          ),
        ),
      )
      _        <- jwks.getPublicKeys.succeedsWith(publicKeys)
      _        <- setup(service, jwks)
      response <- client.batched(request)
    yield (response, service, jwks)

  private val sampleResponse = EdgeService.PermissionsResponse(
    resources = Map(
      ResourceId("central") -> EdgeService.ResourcePermissions(Set(PermissionId("oauth:read"))),
      ResourceId("orders") -> EdgeService.ResourcePermissions(Set(PermissionId("orders:read"))),
    ),
    isProd = false,
  )

  private val permissionsSuite = suite("GET /permissions/me")(
    test("returns 401 when no token is supplied") {
      for
        (response, service, _) <- run(Request.get(URL.decode("/permissions/me?resource=central").toOption.get))
      yield assertTrue(
        response.status == Status.Unauthorized,
        service.getMyPermissions.calls.isEmpty,
      )
    },
    test("passes empty resource list when resource query param is absent") {
      for
        accessToken <- token(clientId = "web-app", tenantId = Some("default"), roles = Some(List("member")))
        request = Request
          .get(URL.decode("/permissions/me").toOption.get)
          .addCookie(Cookie.Request(EdgeSessionCookie.name, s"web-app:$accessToken"))
        emptyResponse = EdgeService.PermissionsResponse(resources = Map.empty)
        (response, service, _) <- run(request, (s, _) => s.getMyPermissions.succeedsWith(emptyResponse))
      yield assertTrue(
        response.status == Status.Ok,
        service.getMyPermissions.calls.map(_._2) == List(Nil),
      )
    },
    test("returns 401 when the token is not a valid JWT") {
      val request = Request
        .get(URL.decode("/permissions/me?resource=central").toOption.get)
        .addCookie(Cookie.Request(EdgeSessionCookie.name, "not-a-jwt"))
      for
        (response, service, _) <- run(request)
      yield assertTrue(
        response.status == Status.Unauthorized,
        service.getMyPermissions.calls.isEmpty,
      )
    },
    test("accepts the EDGE_SESSION cookie, parses claims and repeated resource params") {
      for
        accessToken <- token(
          clientId = "central-admin",
          tenantId = Some("default"),
          roles = Some(List("oauth-admin")),
        )
        request = Request
          .get(URL.decode("/permissions/me?resource=central&resource=orders").toOption.get)
          .addCookie(Cookie.Request(EdgeSessionCookie.name, s"central-admin:$accessToken"))
        (response, service, _) <- run(request, (s, _) => s.getMyPermissions.succeedsWith(sampleResponse))
        payload <- response.body.asString.map(_.fromJson[EdgeService.PermissionsResponse])
      yield assertTrue(
        response.status == Status.Ok,
        payload == Right(sampleResponse),
        service.getMyPermissions.calls == List(
          (
            PermissionsClaims(
              clientId = Some(ClientId("central-admin")),
              tenantId = Some(TenantId.default),
              roles = Some(List(RoleId("oauth-admin"))),
            ),
            List("central", "orders"),
          ),
        ),
      )
    },
    test("accepts an Authorization Bearer token") {
      for
        accessToken <- token(clientId = "web-app", tenantId = Some("default"), roles = Some(List("member")))
        request = Request
          .get(URL.decode("/permissions/me?resource=orders").toOption.get)
          .addHeader(Header.Authorization.Bearer(accessToken))
        (response, service, _) <- run(request, (s, _) => s.getMyPermissions.succeedsWith(sampleResponse))
      yield assertTrue(
        response.status == Status.Ok,
        service.getMyPermissions.calls == List(
          (
            PermissionsClaims(clientId = Some(ClientId("web-app")), tenantId = Some(TenantId.default), roles = Some(List(RoleId("member")))),
            List("orders"),
          ),
        ),
      )
    },
  )

  private val loginSuite = suite("GET /login/{presetId}")(
    test("redirects to the SSO authorize URL on a known preset") {
      val authorizeUrl = URL.decode("https://idp.example/authorize?client_id=web-app").toOption.get
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/login/preset-1").toOption.get),
          (s, _) => s.authorize.succeedsWith(authorizeUrl),
        )
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).map(_.url).contains(authorizeUrl),
        service.authorize.calls == List((PresetId("preset-1"), Map.empty[String, String])),
      )
    },
    test("passes whitelisted query params to authorize") {
      val authorizeUrl = URL.decode("https://idp.example/authorize?client_id=web-app").toOption.get
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/login/preset-1?acr_values=mfa&prompt=login&unknown=ignored").toOption.get),
          (s, _) => s.authorize.succeedsWith(authorizeUrl),
        )
      yield assertTrue(
        response.status == Status.SeeOther,
        service.authorize.calls == List((PresetId("preset-1"), Map("acr_values" -> "mfa", "prompt" -> "login"))),
      )
    },
    test("returns 404 when the preset is unknown") {
      for
        (response, _, _) <- run(
          Request.get(URL.decode("/login/missing").toOption.get),
          (s, _) => s.authorize.failsWith(PresetNotFound()),
        )
      yield assertTrue(response.status == Status.NotFound)
    },
  )

  private val completeSuite = suite("GET /complete")(
    test("redirects to the post-login URL and sets the session cookie") {
      val completion = EdgeService.LoginCompletion(
        presetId = PresetId("preset-default"),
        accessToken = AccessToken("header.payload.sig"),
        cookieTtl = 1.hour,
        postLoginRedirectUri = RedirectUri("https://app.example/home"),
        cookieDomain = Some("app.example"),
        cookiePath = Some("/"),
      )
      for
        (response, _, _) <- run(
          Request.get(URL.decode("/complete?code=c-1&state=s-1").toOption.get),
          (s, _) => s.complete.succeedsWith(completion),
        )
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).map(_.url.encode).contains("https://app.example/home"),
        response.header(Header.SetCookie).map(_.value.name).contains(EdgeSessionCookie.name),
      )
    },
    test("returns 400 when the auth conversation is unknown") {
      for
        (response, _, _) <- run(
          Request.get(URL.decode("/complete?code=c-1&state=s-1").toOption.get),
          (s, _) => s.complete.failsWith(AuthConversationNotFound()),
        )
      yield assertTrue(response.status == Status.BadRequest)
    },
    test("redirects OAuth errors to the post-login URL without setting a session cookie") {
      val redirectUrl = URL.decode("https://app.example/home?from=login").toOption.get.addQueryParams(
        List(
          "error" -> "access_denied",
          "error_description" -> "User cancelled",
          "error_uri" -> "https://idp.example/errors/access_denied",
        ),
      )
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/complete?error=access_denied&error_description=User%20cancelled&error_uri=https%3A%2F%2Fidp.example%2Ferrors%2Faccess_denied&state=s-1").toOption.get),
          (s, _) => s.completeError.succeedsWith(redirectUrl),
        )
        location = response.header(Header.Location).map(_.url)
      yield assertTrue(
        response.status == Status.SeeOther,
        location.contains(redirectUrl),
        response.header(Header.SetCookie).isEmpty,
        service.complete.calls.isEmpty,
        service.completeError.calls.size == 1,
      )
    },
    test("returns 400 when neither code nor error is supplied") {
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/complete?state=s-1").toOption.get),
        )
      yield assertTrue(
        response.status == Status.BadRequest,
        service.complete.calls.isEmpty,
        service.completeError.calls.isEmpty,
      )
    },
  )

  private val frontChannelLogoutSuite = suite("GET /logout/frontchannel")(
    test("clears cookies returned by EdgeService and responds 200 with no-store") {
      val clearCookie = EdgeSessionCookie.clear(domain = Some("app.example"), path = Some("/"))
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/logout/frontchannel?iss=https://idp.example&sid=sso-session-1").toOption.get),
          (s, _) => s.frontChannelLogout.succeedsWith(List(clearCookie)),
        )
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.CacheControl).contains(Header.CacheControl.NoStore),
        response.header(Header.SetCookie).map(_.value.content).contains(""),
        service.frontChannelLogout.calls == List(("https://idp.example", SessionId("sso-session-1"))),
      )
    },
    test("responds 200 with no cookies when the session has no known refresh tokens") {
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/logout/frontchannel?iss=https://idp.example&sid=sso-session-unknown").toOption.get),
          (s, _) => s.frontChannelLogout.succeedsWith(List.empty),
        )
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.SetCookie).isEmpty,
        service.frontChannelLogout.calls == List(("https://idp.example", SessionId("sso-session-unknown"))),
      )
    },
    test("responds 200 with no cookies when the issuer is unknown") {
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/logout/frontchannel?iss=https://untrusted.example&sid=sso-session-1").toOption.get),
          (s, _) => s.frontChannelLogout.succeedsWith(List.empty),
        )
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.SetCookie).isEmpty,
        service.frontChannelLogout.calls == List(("https://untrusted.example", SessionId("sso-session-1"))),
      )
    },
  )

  private val backChannelLogoutSuite = suite("POST /logout/backchannel")(
    test("delegates the logout token to EdgeService and responds 200 with no-store") {
      for
        (response, service, _) <- run(
          Request.post(
            URL.decode("/logout/backchannel").toOption.get,
            Body.fromURLEncodedForm(Form.fromStrings("logout_token" -> "header.payload.sig")),
          ),
          (s, _) => s.backChannelLogout.succeedsWith(()),
        )
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.CacheControl).contains(Header.CacheControl.NoStore),
        service.backChannelLogout.calls == List("header.payload.sig"),
      )
    },
    test("responds 400 without calling EdgeService when logout_token is missing") {
      for
        (response, service, _) <- run(
          Request.post(
            URL.decode("/logout/backchannel").toOption.get,
            Body.fromURLEncodedForm(Form.fromStrings("other" -> "value")),
          ),
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.fromJson[Map[String, String]].toOption.flatMap(_.get("error")).contains("invalid_request"),
        service.backChannelLogout.calls.isEmpty,
      )
    },
    test("responds 400 with the rejection reason when the logout token is invalid") {
      for
        (response, _, _) <- run(
          Request.post(
            URL.decode("/logout/backchannel").toOption.get,
            Body.fromURLEncodedForm(Form.fromStrings("logout_token" -> "not-a-jwt")),
          ),
          (s, _) => s.backChannelLogout.failsWith(InvalidLogoutToken("logout token must not carry a nonce")),
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        response.header(Header.CacheControl).contains(Header.CacheControl.NoStore),
        body.fromJson[Map[String, String]].toOption.flatMap(_.get("error_description"))
          .contains("logout token must not carry a nonce"),
      )
    },
  )

  private val proxySuite = suite("proxy routes")(
    test("GET /resources/{alias}/{rest} delegates to EdgeService.proxy") {
      for
        (response, service, _) <- run(
          Request.get(URL.decode("/resources/my-api/data/123").toOption.get),
          (s, _) => s.proxy.succeedsWith(Response.ok),
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.proxy.calls.map(_._1) == List("my-api"),
      )
    },
    test("POST /resources/{alias}/{rest} delegates to EdgeService.proxy") {
      for
        (response, service, _) <- run(
          Request.post(URL.decode("/resources/orders/items").toOption.get, Body.empty),
          (s, _) => s.proxy.succeedsWith(Response.ok),
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.proxy.calls.map(_._1) == List("orders"),
      )
    },
    test("PUT /resources/{alias}/{rest} delegates to EdgeService.proxy") {
      for
        (response, service, _) <- run(
          Request.put(URL.decode("/resources/orders/items/42").toOption.get, Body.empty),
          (s, _) => s.proxy.succeedsWith(Response.ok),
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.proxy.calls.map(_._1) == List("orders"),
      )
    },
    test("PATCH /resources/{alias}/{rest} delegates to EdgeService.proxy") {
      for
        (response, service, _) <- run(
          Request.patch(URL.decode("/resources/orders/items/42").toOption.get, Body.empty),
          (s, _) => s.proxy.succeedsWith(Response.ok),
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.proxy.calls.map(_._1) == List("orders"),
      )
    },
    test("DELETE /resources/{alias}/{rest} delegates to EdgeService.proxy") {
      for
        (response, service, _) <- run(
          Request.delete(URL.decode("/resources/orders/items/42").toOption.get),
          (s, _) => s.proxy.succeedsWith(Response.ok),
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.proxy.calls.map(_._1) == List("orders"),
      )
    },
  )

  private val sessionCookieSuite = suite("EdgeSessionCookie")(
    test("apply embeds the preset id and sets isSecure, isHttpOnly, and sameSite=Strict") {
      val now = Instant.now()
      val cookie = EdgeSessionCookie(
        presetId = PresetId("p1"),
        accessToken = AccessToken("tok"),
        ttl = 1.hour,
        domain = Some("app.example"),
        path = Some("/"),
        now = now,
      )
      ZIO.succeed(assertTrue(
        cookie.isSecure,
        cookie.isHttpOnly,
        cookie.sameSite.contains(Cookie.SameSite.Strict),
        cookie.content == "p1:tok",
        EdgeSessionCookie.parse(cookie.content) == (PresetId("p1"), AccessToken("tok")),
      ))
    },
    test("apply uses the supplied path") {
      val now = Instant.now()
      val cookie = EdgeSessionCookie(
        presetId = PresetId("p1"),
        accessToken = AccessToken("tok"),
        ttl = 1.hour,
        domain = None,
        path = Some("/app"),
        now = now,
      )
      ZIO.succeed(assertTrue(cookie.path.contains(Path.decode("/app"))))
    },
    test("apply falls back to Path.root when path is None") {
      val now = Instant.now()
      val cookie = EdgeSessionCookie(
        presetId = PresetId("p1"),
        accessToken = AccessToken("tok"),
        ttl = 1.hour,
        domain = None,
        path = None,
        now = now,
      )
      ZIO.succeed(assertTrue(cookie.path.contains(Path.root)))
    },
    test("clear produces empty content, maxAge=Zero, and security attributes") {
      val cookie = EdgeSessionCookie.clear(
        domain = Some("app.example"),
        path = Some("/"),
      )
      ZIO.succeed(assertTrue(
        cookie.content.isEmpty,
        cookie.maxAge.contains(Duration.Zero),
        cookie.isSecure,
        cookie.isHttpOnly,
        cookie.sameSite.contains(Cookie.SameSite.Strict),
      ))
    },
    test("clear falls back to Path.root when path is None") {
      val cookie = EdgeSessionCookie.clear(domain = None, path = None)
      ZIO.succeed(assertTrue(cookie.path.contains(Path.root)))
    },
  )

  def spec = suite("EdgeController")(
    permissionsSuite,
    loginSuite,
    completeSuite,
    frontChannelLogoutSuite,
    backChannelLogoutSuite,
    proxySuite,
    sessionCookieSuite,
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
