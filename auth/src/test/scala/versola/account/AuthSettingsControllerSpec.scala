package versola.account

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.TestEnvConfig
import versola.oauth.challenge.passkey.PasskeyCeremony
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.jwks.JwksService
import versola.user.model.UserId
import versola.util.{Base64, UnitSpecBase}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

import java.time.Instant
import java.util.{Date, UUID}

object AuthSettingsControllerSpec extends UnitSpecBase:

  private val config       = TestEnvConfig.coreConfig
  private val jwksSvc      = TestEnvConfig.jwksService
  private val cookieSecret = config.security.conversationCookieSecret

  private val testUserId   = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  private val testClientId = ClientId("test-client")

  private def createAccessToken(
      userId: UserId,
      clientId: ClientId,
      scope: Set[ScopeToken],
  ): String =
    val now = Instant.now()
    val claims = new JWTClaimsSet.Builder()
      .subject(userId.toString)
      .claim("client_id", clientId.toString)
      .claim("scope", scope.map(_.toString).mkString(" "))
      .claim("jti", "test-access-token-id")
      .audience(clientId.toString)
      .issuer(config.jwt.issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(3600)))
      .build()
    val header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID("test-key-id")
      .`type`(new JOSEObjectType("at+jwt"))
      .build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(config.jwt.privateKey))
    jwt.serialize()

  private def validToken: String =
    createAccessToken(testUserId, testClientId, Set(ScopeToken.AccountSettings))

  /** Build the Cookie request header for SSO_ACCOUNT. */
  private def accountCookieHeader: Header.Cookie =
    Header.Cookie(
      NonEmptyChunk(
        Cookie.Request(
          AuthSettingsCookie.name,
          AuthSettingsCookie.responseCookie(AuthSettingsCookie(testUserId, testClientId), cookieSecret).content,
        ),
      ),
    )

  private val minimalPageData = AuthSettingsPageData(
    sessions        = Nil,
    passkeys        = Vector.empty,
    passkeyResult   = None,
    style           = ".a{}",
    jsCompiled      = Some("/* js */"),
    css             = "body{}",
    locale          = "en",
    locales         = List("en"),
    translations    = Map("sessions_title" -> "Active Sessions"),
    allTranslations = Map("en" -> Map("sessions_title" -> "Active Sessions")),
    pageTitle       = "Account Settings",
  )

  private def buildRoutes(
      service: AuthSettingsService,
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
  ) =
    Observability.handleErrors(
      AuthSettingsController.routes.provideEnvironment(
        ZEnvironment(config) ++
          ZEnvironment(jwksSvc) ++
          ZEnvironment(service) ++
          tracing,
      )
    )

  def spec = suite("AuthSettingsController")(

    // -------------------------------------------------------------------------
    // GET /auth-settings
    // -------------------------------------------------------------------------
    suite("GET /auth-settings")(

      test("without Authorization returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(Request.get(URL.empty / "auth-settings"))
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with an invalid Bearer token returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer("not.a.jwt")),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with Bearer token missing account_settings scope returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          token    = createAccessToken(testUserId, testClientId, Set(ScopeToken.OpenId))
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(token)),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid Bearer token returns 200 HTML page") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.getPageData.succeedsWith(Some(minimalPageData))
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
          body         <- resp.body.asString
          accountCookie = resp.header(Header.SetCookie).map(_.value)
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("versola-form-root"),
          accountCookie.exists(_.name == AuthSettingsCookie.name),
        )
      },

      test("with valid Bearer token and passkey settings sets passkeyRegistration and SSO_PASSKEY_REG cookie") {
        val pageDataWithPasskey = minimalPageData.copy(
          passkeyResult = Some(PasskeyCeremony("reg-req", """{"challenge":"abc"}""")),
        )
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.getPageData.succeedsWith(Some(pageDataWithPasskey))
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
          body        <- resp.body.asString
          cookieNames  = resp.headers.toList.collect { case Header.SetCookie(c) => c.name }.toSet
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("""{\"challenge\":\"abc\"}"""),
          cookieNames.contains(AuthSettingsCookie.name),
          cookieNames.contains(PasskeyRegistrationCookie.name),
        )
      },

      test("page title with HTML special chars is escaped in the rendered HTML") {
        val pageDataWithXss = minimalPageData.copy(pageTitle = "<script>alert(1)</script>")
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.getPageData.succeedsWith(Some(pageDataWithXss))
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
          body <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("&lt;script&gt;"),
          !body.contains("<script>alert"),
        )
      },

      test("with valid Bearer token but service returns None gives 404") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.getPageData.succeedsWith(None)
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
        yield assertTrue(resp.status == Status.NotFound)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/sessions/logout
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/sessions/logout")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(URL.empty / "auth-settings" / "sessions" / "logout", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid SSO_ACCOUNT cookie invalidates session and redirects") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.invalidateSession.succeedsWith(())
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          sessionId = UUID.randomUUID().toString
          resp    <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "sessions" / "logout",
              Body.fromURLEncodedForm(Form.fromStrings("id" -> sessionId)),
            ).addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.SeeOther)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/passkeys/delete
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/passkeys/delete")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "delete", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid SSO_ACCOUNT cookie deletes passkey and redirects") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.deletePasskey.succeedsWith(())
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          credB64  = Base64.urlEncode(Array.fill(32)(0.toByte))
          resp    <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "passkeys" / "delete",
              Body.fromURLEncodedForm(Form.fromStrings("id" -> credB64)),
            ).addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.SeeOther)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/passkeys/register
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/passkeys/register")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with SSO_ACCOUNT but missing SSO_PASSKEY_REG returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty)
              .addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with invalid SSO_PASSKEY_REG content returns 401") {
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty)
              .addHeader(accountCookieHeader)
              .addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(PasskeyRegistrationCookie.name, "invalid.content")))),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid ceremony cookie and response redirects to auth-settings") {
        val ceremony         = PasskeyRegistrationCookie("ceremony-request-json")
        val regCookieContent = PasskeyRegistrationCookie.responseCookie(ceremony, cookieSecret).content
        for
          client  <- ZIO.service[Client]
          tracing <- NoopTracing.layer.build
          svc      = stub[AuthSettingsService]
          _       <- svc.finishPasskeyRegistration.succeedsWith(())
          _       <- TestClient.addRoutes(buildRoutes(svc, tracing))
          resp    <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "passkeys" / "register",
              Body.fromURLEncodedForm(Form.fromStrings("response" -> "{}")),
            ).addHeader(Header.Cookie(NonEmptyChunk(
              Cookie.Request(AuthSettingsCookie.name,
                AuthSettingsCookie.responseCookie(AuthSettingsCookie(testUserId, testClientId), cookieSecret).content),
              Cookie.Request(PasskeyRegistrationCookie.name, regCookieContent),
            ))),
          )
          setCookies = resp.headers.toList.collect { case Header.SetCookie(c) => c }
        yield assertTrue(
          resp.status == Status.SeeOther,
          setCookies.exists(c =>
            c.name == PasskeyRegistrationCookie.name && c.maxAge.contains(zio.Duration.Zero)
          ),
        )
      },
    ),

  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
