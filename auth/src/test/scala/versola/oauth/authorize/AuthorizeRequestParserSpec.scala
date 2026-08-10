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
    secret = None,
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = Some(AuthFlow.default.copy(primary = AuthFlow.default.primary.copy(credentials = List(PrimaryCredential.email, PrimaryCredential.phone)))),
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
  )

  class Env:
    val configuration = stub[OAuthConfigurationService]
    configuration.getResourcesForClient.returnsWith(ZIO.succeed(Nil))
    val parser = AuthorizeRequestParser.Impl(TestEnvConfig.coreConfig, configuration)

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
      test("retains a registered resource URI for the access-token audience") {
        val env = Env()
        val resourceUri = ResourceUri("https://api.example.com")
        val request = Request.get(URL.root.addQueryParams(validParams + ("resource" -> resourceUri)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findResource.succeedsWith(Some(ResourceRecord(ResourceId("api"), tenantId, resourceUri, List(clientId), internal = false)))
          result <- env.parser.parse(request)
        yield assertTrue(result.resources == List(resourceUri))
      },
      test("uses public resources and edge when resource is omitted") {
        val env = Env()
        val publicResource = ResourceUri("https://api.example.com")
        val edgeResource = ResourceUri("resource://edge")
        val resources = List(
          ResourceRecord(ResourceId("api"), tenantId, publicResource, List(clientId), internal = false),
          ResourceRecord(ResourceId("internal"), tenantId, ResourceUri("https://internal.example.com"), List(clientId), internal = true),
          ResourceRecord(ResourceId("reports"), tenantId, ResourceUri("https://reports.internal.example.com"), List(clientId), internal = true),
        )
        val request = Request.get(URL.root.addQueryParams(validParams))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getResourcesForClient.succeedsWith(resources)
          result <- env.parser.parse(request)
        yield assertTrue(result.resources == List(publicResource, edgeResource))
      },
      test("uses edge when resource is omitted and no internal resources are available") {
        val env = Env()
        val publicResource = ResourceUri("https://api.example.com")
        val request = Request.get(URL.root.addQueryParams(validParams))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getResourcesForClient.succeedsWith(List(
            ResourceRecord(ResourceId("api"), tenantId, publicResource, List(clientId), internal = false),
          ))
          result <- env.parser.parse(request)
        yield assertTrue(result.resources == List(publicResource, ResourceUri("resource://edge")))
      },
      test("resolves an internal resource indicator by resource ID") {
        val env = Env()
        val resourceId = ResourceId("internal-api")
        val resourceUri = ResourceUri(s"resource://$resourceId")
        val request = Request.get(URL.root.addQueryParams(validParams + ("resource" -> resourceUri)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findResourceById.succeedsWith(Some(ResourceRecord(resourceId, tenantId, ResourceUri("https://internal.example.com"), List(clientId), internal = true)))
          result <- env.parser.parse(request)
        yield assertTrue(
          result.resources == List(ResourceUri("resource://internal-api")),
          env.configuration.findResourceById.calls == List((tenantId, resourceId)),
        )
      },
      test("accepts an explicit edge resource") {
        val env = Env()
        val edgeResource = ResourceUri("resource://edge")
        val request = Request.get(URL.root.addQueryParams(validParams + ("resource" -> edgeResource)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield assertTrue(result.resources == List(edgeResource))
      },
      test("rejects edge when combined with an explicit internal resource") {
        val env = Env()
        val edgeResource = ResourceUri("resource://edge")
        val internalResource = ResourceUri("resource://internal-api")
        val request = Request.post(
          URL.root,
          Body.fromURLEncodedForm(Form.fromStrings(
            (validParams.toSeq ++ Seq("resource" -> edgeResource, "resource" -> internalResource))*
          )),
        )
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidTarget(redirectUri, Some(State("test-state")), edgeResource.toString)))
      },
    suite("parse POST")(
      test("successfully parses valid form-urlencoded request") {
        val env = Env()
        val request = Request.post(URL.root, Body.fromURLEncodedForm(Form.fromStrings(validParams.toSeq*)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.clientId == clientId)
        },
        test("retains repeated resource parameters") {
          val env = Env()
          val first = ResourceUri("https://api.example.com")
          val second = ResourceUri("https://reports.example.com")
          val body = (validParams.toSeq ++ Seq("resource" -> first, "resource" -> second))
            .map((name, value) => s"$name=${java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)}")
            .mkString("&")
          val request = Request.post(URL.root, Body.fromString(body))
            .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          for
            _ <- env.configuration.find.succeedsWith(Some(clientRecord))
            _ <- env.configuration.findResource.returnsZIO { case (_, resource) =>
              ZIO.succeed(Map(
                first -> ResourceRecord(ResourceId("resource"), tenantId, first, List(clientId), internal = false),
                second -> ResourceRecord(ResourceId("reports"), tenantId, second, List(clientId), internal = false),
              ).get(resource))
            }
            result <- env.parser.parse(request)
          yield assertTrue(result.resources == List(first, second))
        },
    ),
    suite("state")(
      test("accepts state at the maximum allowed length") {
        val env = Env()
        val state = "a" * 128
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("state" -> state)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.state.contains(State(state)))
      },
      test("fails when state exceeds the maximum allowed length") {
        val env = Env()
        val state = "a" * 129
        val request = Request.get(URL.root.addQueryParams(validParams ++ Map("state" -> state)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request).either
        yield
          assertTrue(result == Left(Error.StateInvalid(redirectUri)))
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
