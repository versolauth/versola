package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.*
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, RequestUri, RequestUriReference, State}
import versola.util.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*

object AuthorizeRequestParserSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val tenantId = TenantId("default")

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email")),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = Some(AuthFlow.default.copy(primary = AuthFlow.default.primary.copy(credentials = List(PrimaryCredential.email, PrimaryCredential.phone)))),
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
  )

  private val schemaValidator: JsonSchemaValidator = JsonSchemaValidator.Impl()

  private val paymentType = AuthorizationDetailTypeRecord(
    tenantId = tenantId,
    `type` = AuthorizationDetailType("payment_initiation"),
    schema = """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {
          "type": { "type": "string" },
          "locations": { "type": "array", "items": { "type": "string" } },
          "instructedAmount": {
            "type": "object",
            "properties": { "currency": { "type": "string" }, "amount": { "type": "string" } },
            "required": ["currency", "amount"]
          }
        },
        "required": ["type", "instructedAmount"],
        "unevaluatedProperties": false
      }
    """.fromJson[Json.Obj].toOption.get,
  )

  class Env:
    val configuration = stub[OAuthConfigurationService]
    configuration.getResourcesForClient.returnsWith(ZIO.succeed(Nil))
    configuration.getIpHeader.returnsWith(ZIO.succeed("X-Real-IP"))
    val pushedAuthorizationRepository = stub[PushedAuthorizationRepository]
    val securityService = stub[SecurityService]
    val parser = AuthorizeRequestParser.Impl(
      TestEnvConfig.coreConfig,
      configuration,
      pushedAuthorizationRepository,
      securityService,
      schemaValidator,
    )

  def validParams = Map(
    "client_id" -> clientId.toString,
    "redirect_uri" -> redirectUri.encode,
    "response_type" -> "code",
    "scope" -> "openid profile",
    "state" -> "test-state",
    "code_challenge" -> "a" * 43,
    "code_challenge_method" -> "S256"
  )

  private val requestUriReference = RequestUriReference(Array.fill(32)(6.toByte))
  private val requestUri = RequestUri(requestUriReference)
  private val requestUriMac = MAC(Array.fill(32)(7.toByte))
  private val pushedRecord = PushedAuthorizationRecord(clientId, validParams.view.mapValues(List(_)).toMap)

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
        test("splits a single comma-joined resource field, as zio-http produces from a repeated form field") {
          val env = Env()
          val first = ResourceUri("https://api.example.com")
          val second = ResourceUri("resource://internal-api")
          val body = (validParams.toSeq ++ Seq("resource" -> s"$first,$second"))
            .map((name, value) => s"$name=${java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)}")
            .mkString("&")
          val request = Request.post(URL.root, Body.fromString(body))
            .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          for
            _ <- env.configuration.find.succeedsWith(Some(clientRecord))
            _ <- env.configuration.findResource.succeedsWith(
              Some(ResourceRecord(ResourceId("resource"), tenantId, first, List(clientId), internal = false)),
            )
            _ <- env.configuration.findResourceById.succeedsWith(
              Some(ResourceRecord(ResourceId("internal-api"), tenantId, ResourceUri("https://internal.example.com"), List(clientId), internal = true)),
            )
            result <- env.parser.parse(request)
          yield assertTrue(result.resources == List(first, second))
        },
    ),
    suite("authorization_details")(
      test("retains details that satisfy the registered type schema") {
        val env = Env()
        val details = """[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"}}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          result <- env.parser.parse(request)
        yield assertTrue(
          result.authorizationDetails.map(_.map(_.`type`)) == Some(List(AuthorizationDetailType("payment_initiation"))),
        )
      },
      test("rejects an unregistered type") {
        val env = Env()
        val details = """[{"type":"unknown_type"}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(None)
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidAuthorizationDetails(
          redirectUri,
          Some(State("test-state")),
          "unknown_type - unknown authorization details type",
        )))
      },
      test("rejects an unknown member of a registered type") {
        val env = Env()
        val details = """[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"},"extra":1}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          result <- env.parser.parse(request).either
        yield assertTrue(result.left.exists {
          case Error.InvalidAuthorizationDetails(_, _, reason) => reason.startsWith("payment_initiation - ")
          case _ => false
        })
      },
      test("rejects a detail without a type member") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> """[{"actions":["read"]}]""")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidAuthorizationDetails(
          redirectUri,
          Some(State("test-state")),
          "Authorization detail is missing the required type member",
        )))
      },
      test("rejects a value that is not a JSON array") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> """{"type":"payment_initiation"}""")))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidAuthorizationDetails(
          redirectUri,
          Some(State("test-state")),
          "authorization_details must be a JSON array",
        )))
      },
      test("rejects a location that is not a registered resource") {
        val env = Env()
        val details = """[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"},"locations":["https://unknown.example.com"]}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          _ <- env.configuration.findResource.succeedsWith(None)
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidAuthorizationDetails(
          redirectUri,
          Some(State("test-state")),
          "payment_initiation - unknown location - https://unknown.example.com",
        )))
      },
      test("accepts a location that is a registered resource") {
        val env = Env()
        val location = ResourceUri("https://api.example.com")
        val details = s"""[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"},"locations":["$location"]}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          _ <- env.configuration.findResource.succeedsWith(
            Some(ResourceRecord(ResourceId("api"), tenantId, location, List(clientId), internal = false)),
          )
          result <- env.parser.parse(request)
        yield assertTrue(result.authorizationDetails.map(_.map(_.locations)) == Some(List(List(location))))
      },
      test("accepts the edge resource indicator as a location, like the resource parameter") {
        val env = Env()
        val edgeResource = ResourceUri("resource://edge")
        val details = s"""[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"},"locations":["$edgeResource"]}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          result <- env.parser.parse(request)
        yield assertTrue(result.authorizationDetails.map(_.map(_.locations)) == Some(List(List(edgeResource))))
      },
      test("rejects the edge resource indicator combined with an internal resource location") {
        val env = Env()
        val edgeResource = ResourceUri("resource://edge")
        val internalResource = ResourceUri("resource://internal-api")
        val details = s"""[{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"},"locations":["$edgeResource","$internalResource"]}]"""
        val request = Request.get(URL.root.addQueryParams(validParams + ("authorization_details" -> details)))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.InvalidAuthorizationDetails(
          redirectUri,
          Some(State("test-state")),
          s"payment_initiation - unknown location - $edgeResource",
        )))
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
    suite("ip")(
      test("extracts the ip from the tenant-configured header") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams)).addHeader("X-Real-IP", "9.9.9.9")
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.ip.contains("9.9.9.9"))
      },
      test("takes the first value for multi-value headers") {
        val env = Env()
        env.configuration.getIpHeader.returnsWith(ZIO.succeed("X-Forwarded-For"))
        val request = Request.get(URL.root.addQueryParams(validParams)).addHeader("X-Forwarded-For", "7.7.7.7, 10.0.0.1")
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.ip.contains("7.7.7.7"))
      },
      test("is None when the configured header is absent from the request") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(validParams))
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield
          assertTrue(result.ip.isEmpty)
      },
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
    ),
    suite("request_uri")(
      test("replaces the request payload with the pushed one") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "client_id" -> clientId.toString,
          "request_uri" -> requestUri,
        )))
        for
          _ <- env.securityService.mac.succeedsWith(requestUriMac)
          _ <- env.pushedAuthorizationRepository.consume.succeedsWith(Some(pushedRecord))
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield assertTrue(
          result.clientId == clientId,
          result.redirectUri == redirectUri,
          result.state == Some(State("test-state")),
          env.pushedAuthorizationRepository.consume.calls.map(_.toSeq) == List(requestUriMac.toSeq),
        )
      },
      test("ignores parameters sent alongside the request_uri") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "client_id" -> clientId.toString,
          "request_uri" -> requestUri,
          "scope" -> "openid profile email",
        )))
        for
          _ <- env.securityService.mac.succeedsWith(requestUriMac)
          _ <- env.pushedAuthorizationRepository.consume.succeedsWith(Some(pushedRecord))
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          result <- env.parser.parse(request)
        yield assertTrue(result.scope == Set(ScopeToken("openid"), ScopeToken("profile")))
      },
      test("fails when the request_uri is unknown or expired") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "client_id" -> clientId.toString,
          "request_uri" -> requestUri,
        )))
        for
          _ <- env.securityService.mac.succeedsWith(requestUriMac)
          _ <- env.pushedAuthorizationRepository.consume.succeedsWith(None)
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.BadRequest))
      },
      test("fails when the request_uri is not a pushed request reference") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "client_id" -> clientId.toString,
          "request_uri" -> "https://client.example.org/request.jwt",
        )))
        for
          result <- env.parser.parse(request).either
        yield assertTrue(
          result == Left(Error.BadRequest),
          env.pushedAuthorizationRepository.consume.calls.isEmpty,
        )
      },
      test("fails when the request_uri was pushed by another client") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "client_id" -> "other-client",
          "request_uri" -> requestUri,
        )))
        for
          _ <- env.securityService.mac.succeedsWith(requestUriMac)
          _ <- env.pushedAuthorizationRepository.consume.succeedsWith(Some(pushedRecord))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.BadRequest))
      },
      test("fails when client_id is missing alongside a request_uri") {
        val env = Env()
        val request = Request.get(URL.root.addQueryParams(Map(
          "request_uri" -> requestUri,
        )))
        for
          _ <- env.securityService.mac.succeedsWith(requestUriMac)
          _ <- env.pushedAuthorizationRepository.consume.succeedsWith(Some(pushedRecord))
          result <- env.parser.parse(request).either
        yield assertTrue(result == Left(Error.BadRequest))
      },
    ),
  )
