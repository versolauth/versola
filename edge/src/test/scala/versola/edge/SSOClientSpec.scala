package versola.edge

import versola.edge.model.*
import versola.util.{Base64, RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator

object SSOClientSpec extends ZIOSpecDefault:

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
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val basePreset = AuthorizationPreset(
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

  private val state = State.fromBytes(Array.fill(16)(1.toByte))

  private def queryParam(url: URL, key: String): Option[Chunk[String]] =
    url.queryParams.map.get(key)

  def spec = suite("SSOClient")(authorizeUriSuite, exchangeSuite, refreshSuite, userInfoSuite)

  private val authorizeUriSuite = suite("SSOClient.authorizeUri")(
    test("overrideParams replace a same-named key in customParameters") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(customParameters = Map("prompt" -> List("none")))
        url <- sso.authorizeUri(preset, "challenge", state, Map("prompt" -> "login"))
      yield assertTrue(
        queryParam(url, "prompt") == Some(Chunk("login")),
      )
    },
    test("overrideParams replace ui_locales from preset.uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(uiLocales = Some(List("en", "fr")))
        url <- sso.authorizeUri(preset, "challenge", state, Map("ui_locales" -> "de"))
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("de")),
      )
    },
    test("overrideParams are added when not present in preset") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        url <- sso.authorizeUri(basePreset, "challenge", state, Map("acr_values" -> "mfa"))
      yield assertTrue(
        queryParam(url, "acr_values") == Some(Chunk("mfa")),
      )
    },
    test("omits scope when the preset does not specify one") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        url <- sso.authorizeUri(basePreset.copy(scope = Set.empty), "challenge", state)
      yield assertTrue(queryParam(url, "scope").isEmpty)
    },
    test("empty overrideParams preserves customParameters and uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(
          uiLocales = Some(List("en")),
          customParameters = Map("prompt" -> List("none")),
        )
        url <- sso.authorizeUri(preset, "challenge", state, Map.empty)
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("en")),
        queryParam(url, "prompt") == Some(Chunk("none")),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val clientId = ClientId("web-app")
  private val clientSecret = Secret("s3cret".getBytes("UTF-8").nn)
  private val redirectUri = RedirectUri("https://app.example/callback")

  private val tokenJson =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1","refresh_token_expires_in":7200,"scope":"openid","id_token":"id-1"}"""

  private def respondWith(response: Response) =
    TestClient.addRoutes(Handler.succeed(response).toRoutes)

  private def captureRequest(seen: Ref[Option[Request]], response: Response) =
    TestClient.addRoutes(
      Handler.fromFunctionZIO[Request](r =>
        r.body.asURLEncodedForm.orDie.flatMap(form =>
          seen.set(Some(r.copy(body = Body.fromURLEncodedForm(form)))).as(response),
        ),
      ).toRoutes,
    )

  private val exchangeSuite = suite("SSOClient.exchangeAuthorizationCode")(
    test("decodes the token response on success") {
      for
        _ <- respondWith(Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        result <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, clientSecret)
      yield assertTrue(
        result.accessToken == AccessToken("at-1"),
        result.tokenType == "Bearer",
        result.expiresIn == 3600L,
        result.refreshToken == Some(RefreshToken("rt-1")),
        result.scope == Some("openid"),
        result.idToken == Some("id-1"),
      )
    },
    test("posts the authorization_code grant as a urlencoded form with basic auth") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        _ <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, clientSecret)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
      yield assertTrue(
        request.method == Method.POST,
        request.url.path.toString.endsWith("token"),
        form.get("grant_type").flatMap(_.stringValue) == Some("authorization_code"),
        form.get("code").flatMap(_.stringValue) == Some("c-1"),
        form.get("code_verifier").flatMap(_.stringValue) == Some("v-1"),
        form.get("redirect_uri").flatMap(_.stringValue) == Some(redirectUri.toString),
        request.header(Header.Authorization).contains(
          Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret)),
        ),
      )
    },
    test("fails with the error and description reported by the server") {
      for
        _ <- respondWith(
          Response.json("""{"error":"invalid_request","error_description":"code expired"}""").status(Status.BadRequest),
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, clientSecret).flip
      yield assertTrue(
        error.getMessage.nn.contains("400"),
        error.getMessage.nn.contains("invalid_request"),
        error.getMessage.nn.contains("code expired"),
      )
    },
    test("omits the description when the server does not send one") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_client"}""").status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, clientSecret).flip
      yield assertTrue(
        error.getMessage.nn.contains("invalid_client"),
        !error.getMessage.nn.contains(" - "),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val refreshSuite = suite("SSOClient.exchangeRefreshToken")(
    test("decodes the token response on success") {
      for
        _ <- respondWith(Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        result <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, clientSecret)
      yield assertTrue(result.accessToken == AccessToken("at-1"))
    },
    test("posts the refresh_token grant as a urlencoded form") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        _ <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, clientSecret)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
      yield assertTrue(
        form.get("grant_type").flatMap(_.stringValue) == Some("refresh_token"),
        form.get("refresh_token").flatMap(_.stringValue) == Some("rt-0"),
      )
    },
    test("maps an invalid_grant error to InvalidGrant rather than a failure") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_grant"}""").status(Status.BadRequest))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, clientSecret).flip
      yield assertTrue(error == SSOClient.InvalidGrant)
    },
    test("fails with the reported error for any other rejection") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_client"}""").status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, clientSecret).flip
      yield assertTrue(
        error.isInstanceOf[RuntimeException],
        error.asInstanceOf[RuntimeException].getMessage.nn.contains("invalid_client"),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val userInfoSuite = suite("SSOClient.userInfo")(
    test("returns the claims object on success") {
      for
        _ <- respondWith(Response.json("""{"sub":"user-1"}"""))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        claims <- sso.userInfo(AccessToken("at-1"))
      yield assertTrue(claims.get("sub").flatMap(_.asString) == Some("user-1"))
    },
    test("sends the access token as a bearer credential") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json("""{"sub":"user-1"}"""))).toRoutes,
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        _ <- sso.userInfo(AccessToken("at-1"))
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        request.url.path.toString.endsWith("userinfo"),
        request.header(Header.Authorization).exists {
          case Header.Authorization.Bearer(t) => t.stringValue == "at-1"
          case _ => false
        },
      )
    },
    test("rejects a non-object JSON body") {
      for
        _ <- respondWith(Response.json("""["not","an","object"]"""))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.userInfo(AccessToken("at-1")).flip
      yield assertTrue(error.isInstanceOf[RuntimeException])
    },
    test("maps 401 to UserInfoUnauthorized") {
      for
        _ <- respondWith(Response.status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.userInfo(AccessToken("at-1")).flip
      yield assertTrue(error == SSOClient.UserInfoUnauthorized)
    },
    test("fails for any other error status") {
      for
        _ <- respondWith(Response.status(Status.InternalServerError))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        error <- sso.userInfo(AccessToken("at-1")).flip
      yield assertTrue(
        error.isInstanceOf[RuntimeException],
        error.asInstanceOf[RuntimeException].getMessage.nn.contains("500"),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging
