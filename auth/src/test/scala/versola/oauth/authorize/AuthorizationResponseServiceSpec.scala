package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.ResponseMode
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.util.{JWT, Secret, UnitSpecBase}
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*

object AuthorizationResponseServiceSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val config = TestEnvConfig.coreConfig

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid")),
    secret = Some(Secret(Array.fill(32)(4.toByte))),
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
  )

  private val params = List("code" -> "the-code", "iss" -> config.jwt.issuer, "state" -> "test-state")

  class Env:
    val configuration = stub[OAuthConfigurationService]
    val service = AuthorizationResponseService.Impl(config, configuration, TestEnvConfig.jwksService)

  /** The claims of the `response` JWT the redirect carries, verified against the JWKS the
    * tenant's key was published in -- so a test that asserts on them has also asserted that
    * the client could have.
    */
  private def responseClaims(url: URL, fromFragment: Boolean): Task[Json.Obj] =
    val raw =
      if fromFragment then url.fragment.map(_.raw).getOrElse("")
      else url.queryParams.queryParam("response").getOrElse("")
    val jwt =
      if fromFragment then
        raw.split('&').iterator
          .map(_.split('=').toList)
          .collectFirst { case "response" :: value :: Nil => java.net.URLDecoder.decode(value, "UTF-8") }
          .getOrElse("")
      else raw
    JWT.deserialize[Json.Obj](jwt, TestEnvConfig.publicKeys, JWT.Type.JWT)
      .mapError(error => RuntimeException(s"not a verifiable response JWT: $error"))

  def spec = suite("AuthorizationResponseService")(
    suite("plain response modes")(
      test("places the parameters in the query string") {
        val env = Env()
        for
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.Query, params)
        yield assertTrue(
          url.queryParams.queryParam("code") == Some("the-code"),
          url.queryParams.queryParam("state") == Some("test-state"),
          url.fragment.isEmpty,
        )
      },
      test("places the parameters in the fragment") {
        val env = Env()
        for
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.Fragment, params)
        yield assertTrue(
          url.fragment.exists(_.raw.contains("code=the-code")),
          url.queryParams.queryParam("code").isEmpty,
        )
      },
      test("does not resolve a signing key it has no use for") {
        val env = Env()
        for
          _ <- env.service.redirect(clientId, redirectUri, ResponseMode.Query, params)
        yield assertTrue(env.configuration.get.calls.isEmpty)
      },
    ),
    suite("JARM")(
      test("returns every parameter as a claim of a signed response JWT") {
        val env = Env()
        for
          _ <- env.configuration.get.succeedsWith(clientRecord)
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.QueryJwt, params)
          claims <- responseClaims(url, fromFragment = false)
        yield assertTrue(
          claims.get("code") == Some(Json.Str("the-code")),
          claims.get("state") == Some(Json.Str("test-state")),
          claims.get("aud") == Some(Json.Arr(Json.Str(clientId))),
          claims.get("iss") == Some(Json.Str(config.jwt.issuer)),
          claims.get("exp").isDefined,
        )
      },
      test("carries the issuer only as the JWT's own claim, not as a parameter beside it") {
        val env = Env()
        for
          _ <- env.configuration.get.succeedsWith(clientRecord)
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.QueryJwt, params)
        yield assertTrue(
          url.queryParams.queryParam("iss").isEmpty,
          url.queryParams.queryParam("code").isEmpty,
          url.queryParams.queryParam("state").isEmpty,
          url.queryParams.queryParam("response").isDefined,
        )
      },
      test("signs an error response the same way as a successful one") {
        val env = Env()
        val errorParams = List(
          "error" -> "access_denied",
          "error_description" -> "The resource owner denied the request",
          "iss" -> config.jwt.issuer,
        )
        for
          _ <- env.configuration.get.succeedsWith(clientRecord)
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.QueryJwt, errorParams)
          claims <- responseClaims(url, fromFragment = false)
        yield assertTrue(
          claims.get("error") == Some(Json.Str("access_denied")),
          claims.get("error_description") == Some(Json.Str("The resource owner denied the request")),
        )
      },
      test("places the response JWT in the fragment for fragment.jwt") {
        val env = Env()
        for
          _ <- env.configuration.get.succeedsWith(clientRecord)
          url <- env.service.redirect(clientId, redirectUri, ResponseMode.FragmentJwt, params)
          claims <- responseClaims(url, fromFragment = true)
        yield assertTrue(
          url.queryParams.queryParam("response").isEmpty,
          claims.get("code") == Some(Json.Str("the-code")),
        )
      },
    ),
  )
