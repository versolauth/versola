package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, State}
import versola.util.UnitSpecBase
import zio.http.{Request, URL}
import zio.prelude.NonEmptySet
import zio.test.*
import zio.{Chunk, UIO}

object AuthorizeRequestParserSpec extends UnitSpecBase:

  val validClientId = ClientId("test-client")
  val unknownClientId = ClientId("unknown-client")
  val validRedirectUriString = "https://example.com/callback"
  val validRedirectUri = URL.decode(validRedirectUriString).toOption.get
  val invalidRedirectUri = "https://example.com/callback#fragment"
  val relativeRedirectUri = "/callback"
  val unregisteredRedirectUri = "https://other.com/callback"
  val validCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  val invalidCodeChallenge = "too-short"
  val validScope = "read,write"
  val validState = State("random-state")

  val testClient = OAuthClientRecord(
    id = validClientId,
    clientName = "Test Client",
    redirectUris = NonEmptySet(validRedirectUriString, "https://example.com/callback2"),
    scope = Set("read", "write", "admin"),
    secret = None,
    previousSecret = None,
  )

  class Env:
    val oauthClientService = stub[OAuthClientService]
    val parser = AuthorizeRequestParser.Impl(oauthClientService)

  def validRequest(
      clientId: String = validClientId,
      redirectUri: String = validRedirectUriString,
      responseType: String = "code",
      codeChallenge: String = validCodeChallenge,
      codeChallengeMethod: Option[String] = Some("S256"),
      scope: String = validScope,
      state: Option[String] = Some("random-state"),
  ): Request =
    val queryParams = Map(
      "client_id" -> clientId,
      "redirect_uri" -> redirectUri,
      "response_type" -> responseType,
      "code_challenge" -> codeChallenge,
      "scope" -> scope,
    ) ++
      codeChallengeMethod.map(m => "code_challenge_method" -> m).toMap ++
      state.map(s => "state" -> s).toMap

    Request.get(URL.root.addQueryParams(queryParams))

  val spec = suite("AuthorizeRequestParser")(
    successfulParsingTests,
    redirectUriValidationTests,
    clientValidationTests,
    responseTypeValidationTests,
    codeChallengeValidationTests,
    scopeValidationTests,
    multipleValuesTests,
  )

  def successfulParsingTests = suite("successful parsing")(
    test("parse valid request with all parameters") {
      val env = Env()
      val request = validRequest()

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.clientId == validClientId,
        result.redirectUri == validRedirectUri,
        result.responseType == NonEmptySet(ResponseTypeEntry.Code),
        result.codeChallenge == CodeChallenge(validCodeChallenge),
        result.codeChallengeMethod == CodeChallengeMethod.S256,
        result.scope == Set(ScopeToken("read"), ScopeToken("write")),
        result.state == Some(State("random-state")),
      )
    },
    test("parse request with plain code challenge method") {
      val env = Env()
      val request = validRequest(codeChallengeMethod = Some("plain"))

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.codeChallengeMethod == CodeChallengeMethod.Plain,
      )
    },
    test("parse request without code_challenge_method (defaults to plain)") {
      val env = Env()
      val request = validRequest(codeChallengeMethod = None)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.codeChallengeMethod == CodeChallengeMethod.Plain,
      )
    },
    test("parse request without state parameter") {
      val env = Env()
      val request = validRequest(state = None)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.state.isEmpty,
      )
    },
    test("parse request with 'code id_token' response type") {
      val env = Env()
      val request = validRequest(responseType = "code id_token")

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.responseType == NonEmptySet(ResponseTypeEntry.Code, ResponseTypeEntry.IdToken),
      )
    },
  )

  def redirectUriValidationTests = suite("redirect_uri validation")(
    test("fail when redirect_uri is missing") {
      val env = Env()
      val queryParams = Map(
        "client_id" -> validClientId.toString,
        "response_type" -> "code",
        "code_challenge" -> validCodeChallenge,
        "code_challenge_method" -> "S256",
        "scope" -> validScope,
        "state" -> "random-state",
      )
      val request = Request.get(URL.root.addQueryParams(queryParams))

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when redirect_uri is relative") {
      val env = Env()
      val request = validRequest(redirectUri = relativeRedirectUri)

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when redirect_uri contains fragment") {
      val env = Env()
      val request = validRequest(redirectUri = invalidRedirectUri)

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when redirect_uri is not registered for client") {
      val env = Env()
      val request = validRequest(redirectUri = unregisteredRedirectUri)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
  )

  def clientValidationTests = suite("client_id validation")(
    test("fail when client_id is missing") {
      val env = Env()
      val queryParams = Map(
        "redirect_uri" -> validRedirectUriString,
        "response_type" -> "code",
        "code_challenge" -> validCodeChallenge,
        "code_challenge_method" -> "S256",
        "scope" -> validScope,
        "state" -> "random-state",
      )
      val request = Request.get(URL.root.addQueryParams(queryParams))

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when client_id is unknown") {
      val env = Env()
      val request = validRequest(clientId = unknownClientId)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(None)
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
  )

  def responseTypeValidationTests = suite("response_type validation")(
    test("fail when response_type is missing") {
      val env = Env()
      val queryParams = Map(
        "client_id" -> validClientId.toString,
        "redirect_uri" -> validRedirectUriString,
        "code_challenge" -> validCodeChallenge,
        "code_challenge_method" -> "S256",
        "scope" -> validScope,
        "state" -> "random-state",
      )
      val request = Request.get(URL.root.addQueryParams(queryParams))

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.ResponseTypeMissing(
            uri = validRedirectUri,
            state = Some(validState),
          ),
        ),
      )
    },
    test("fail when response_type is unsupported") {
      val env = Env()
      val request = validRequest(responseType = "token")

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.UnsupportedResponseType(
            uri = validRedirectUri,
            state = Some(validState),
            responseType = "token",
          ),
        ),
      )
    },
  )

  def codeChallengeValidationTests = suite("code_challenge validation")(
    test("fail when code_challenge is missing") {
      val env = Env()
      val queryParams = Map(
        "client_id" -> validClientId.toString,
        "redirect_uri" -> validRedirectUriString,
        "response_type" -> "code",
        "code_challenge_method" -> "S256",
        "scope" -> validScope,
        "state" -> "random-state",
      )
      val request = Request.get(URL.root.addQueryParams(queryParams))

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.CodeChallengeMissing(
            uri = validRedirectUri,
            state = Some(validState),
          ),
        ),
      )
    },
    test("fail when code_challenge is invalid (too short)") {
      val env = Env()
      val request = validRequest(codeChallenge = invalidCodeChallenge)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.CodeChallengeInvalid(
            uri = validRedirectUri,
            state = Some(validState),
            value = invalidCodeChallenge,
          ),
        ),
      )
    },
    test("fail when code_challenge_method is invalid") {
      val env = Env()
      val request = validRequest(codeChallengeMethod = Some("invalid"))

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.CodeChallengeMethodInvalid(
            uri = validRedirectUri,
            state = Some(validState),
            value = "invalid",
          ),
        ),
      )
    },
  )

  def scopeValidationTests = suite("scope validation")(
    test("fail when scope is missing") {
      val env = Env()
      val queryParams = Map(
        "client_id" -> validClientId.toString,
        "redirect_uri" -> validRedirectUriString,
        "response_type" -> "code",
        "code_challenge" -> validCodeChallenge,
        "code_challenge_method" -> "S256",
        "state" -> "random-state",
      )
      val request = Request.get(URL.root.addQueryParams(queryParams))

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.ScopeMissing(
            uri = validRedirectUri,
            state = Some(validState),
          ),
        ),
      )
    },
    test("parse single scope") {
      val env = Env()
      val request = validRequest(scope = "read")

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.scope == Set(ScopeToken("read")),
      )
    },
    test("parse multiple scopes") {
      val env = Env()
      val request = validRequest(scope = "read,write,admin")

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request)
      yield assertTrue(
        result.scope == Set(ScopeToken("read"), ScopeToken("write"), ScopeToken("admin")),
      )
    },
  )

  def multipleValuesTests = suite("multiple values rejection")(
    test("fail when client_id has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", "client1")
        .addQueryParam("client_id", "client2")
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when redirect_uri has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", "uri1")
        .addQueryParam("redirect_uri", "uri2")
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.BadRequest),
      )
    },
    test("fail when response_type has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("response_type", "token")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.MultipleValuesProvided(
            uri = validRedirectUri,
            state = Some(State("random-state")),
            queryParamName = "response_type",
          ),
        ),
      )
    },
    test("fail when code_challenge has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", "challenge1")
        .addQueryParam("code_challenge", "challenge2")
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(
          Error.MultipleValuesProvided(
            uri = validRedirectUri,
            state = Some(State("random-state")),
            queryParamName = "code_challenge",
          ),
        ),
      )
    },
    test("fail when code_challenge_method has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("code_challenge_method", "plain")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.MultipleValuesProvided(
          uri = validRedirectUri,
          state = Some(State("random-state")),
          queryParamName = "code_challenge_method",
        )),
      )
    },
    test("fail when scope has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", "read")
        .addQueryParam("scope", "write")
        .addQueryParam("state", "random-state")
      val request = Request.get(url)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.MultipleValuesProvided(
          uri = validRedirectUri,
          state = Some(State("random-state")),
          queryParamName = "scope",
        )),
      )
    },
    test("fail when state has multiple values") {
      val env = Env()
      val url = URL.root
        .addQueryParam("client_id", validClientId.toString)
        .addQueryParam("redirect_uri", validRedirectUriString)
        .addQueryParam("response_type", "code")
        .addQueryParam("code_challenge", validCodeChallenge)
        .addQueryParam("code_challenge_method", "S256")
        .addQueryParam("scope", validScope)
        .addQueryParam("state", "state1")
        .addQueryParam("state", "state2")
      val request = Request.get(url)

      for
        _ <- env.oauthClientService.findCached.succeedsWith(Some(testClient))
        result <- env.parser.parse(request).either
      yield assertTrue(
        result == Left(Error.MultipleValuesProvided(
          uri = validRedirectUri,
          state = None,
          queryParamName = "state",
        )),
      )
    },
  )
