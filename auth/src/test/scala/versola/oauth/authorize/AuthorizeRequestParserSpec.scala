package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.*
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, State}
import versola.util.*
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.test.*

object AuthorizeRequestParserSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val tenantId = TenantId("default")

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Test Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = Some(AuthFlow.default.copy(primary = AuthFlow.default.primary.copy(credentials = List(PrimaryCredential.email, PrimaryCredential.phone)))),
    otpTemplateId = "default",
  )

  class Env:
    val configuration = stub[OAuthConfigurationService]
    val parser = AuthorizeRequestParser.Impl(configuration)

  def validParams = Map(
    "client_id" -> clientId.toString,
    "redirect_uri" -> redirectUri.encode,
    "response_type" -> "code",
    "scope" -> "openid profile",
    "state" -> "test-state",
    "code_challenge" -> "a" * 43,
    "code_challenge_method" -> "S256"
  )

  def spec = suite("AuthorizeRequestParser")(
    suite("parse GET")(
      test("successfully parses valid request") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.clientId == clientId) &&
          assertTrue(result.redirectUri == redirectUri) &&
          assertTrue(result.scope == Set(ScopeToken("openid"), ScopeToken("profile"))) &&
          assertTrue(result.state.contains(State("test-state")))
      },
      test("fails when client_id is missing") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams - "client_id"))
        for
          result <- env.parser.parse(request).either
        yield
          assertTrue(result == Left(Error.BadRequest))
      },
      test("fails when redirect_uri is not whitelisted") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("redirect_uri" -> "https://attacker.com")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request).either
        yield
          assertTrue(result == Left(Error.BadRequest))
      },
      test("successfully parses prompt parameter") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("prompt" -> "login consent")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.prompt == Set(Prompt.login, Prompt.consent))
      }
    ),
    suite("parse POST")(
      test("successfully parses valid form-urlencoded request") {
        val env = Env()
        val request = Request.post(URL.root, Body.fromURLEncodedForm(Form.fromStrings(validParams.toSeq*)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.clientId == clientId)
      }
    ),
    suite("login_hint")(
      test("parses email login_hint") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("login_hint" -> "user@example.com")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.loginHint == Some(Left(Email("user@example.com"))))
      },
      test("parses phone login_hint") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("login_hint" -> "+12025551234")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.loginHint == Some(Right(Phone("+12025551234"))))
      }
    )
  )
