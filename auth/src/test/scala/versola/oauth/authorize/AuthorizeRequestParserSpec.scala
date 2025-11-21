package versola.oauth.authorize

import versola.oauth.OauthClientService
import versola.oauth.authorize.model.{AuthorizeRequest, Error, RenderableError, ResponseTypeEntry}
import versola.oauth.model.{ClientId, CodeChallenge, CodeChallengeMethod, OAuthClient, ScopeToken, State}
import versola.util.UnitSpecBase
import zio.http.URL
import zio.prelude.NonEmptySet
import zio.test.*
import zio.{Chunk, UIO}

object AuthorizeRequestParserSpec extends UnitSpecBase:

  val validClientId = ClientId("test-client")
  val unknownClientId = ClientId("unknown-client")
  val validRedirectUri = "https://example.com/callback"
  val invalidRedirectUri = "https://example.com/callback#fragment"
  val relativeRedirectUri = "/callback"
  val unregisteredRedirectUri = "https://other.com/callback"
  val validCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  val invalidCodeChallenge = "too-short"
  val validScope = "read,write"

  val testClient = OAuthClient(
    id = validClientId,
    clientName = "Test Client",
    redirectUris = NonEmptySet(validRedirectUri, "https://example.com/callback2"),
    scope = Set("read", "write", "admin"),
    secretHash = None,
    secretSalt = None,
    previousSecretHash = None,
    previousSecretSalt = None,
  )

  class Env:
    val oauthClientService = stub[OauthClientService]
    val parser = AuthorizeRequestParser.Impl(oauthClientService)

  def validParams(
      clientId: String = validClientId,
      redirectUri: String = validRedirectUri,
      responseType: String = "code",
      codeChallenge: String = validCodeChallenge,
      codeChallengeMethod: Option[String] = Some("S256"),
      scope: String = validScope,
      state: Option[String] = Some("random-state"),
  ): Map[String, Chunk[String]] =
    Map(
      "client_id" -> Chunk(clientId),
      "redirect_uri" -> Chunk(redirectUri),
      "response_type" -> Chunk(responseType),
      "code_challenge" -> Chunk(codeChallenge),
      "scope" -> Chunk(scope),
    ) ++
      codeChallengeMethod.map(m => "code_challenge_method" -> Chunk(m)).toMap ++
      state.map(s => "state" -> Chunk(s)).toMap

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
      val params = validParams()

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.clientId == validClientId,
        result.redirectUri == URL.decode(validRedirectUri).toOption.get,
        result.responseType == NonEmptySet(ResponseTypeEntry.Code),
        result.codeChallenge == CodeChallenge(validCodeChallenge),
        result.codeChallengeMethod == CodeChallengeMethod.S256,
        result.scope == Set(ScopeToken("read"), ScopeToken("write")),
        result.state == Some(State("random-state")),
      )
    },
    test("parse request with plain code challenge method") {
      val env = Env()
      val params = validParams(codeChallengeMethod = Some("plain"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.codeChallengeMethod == CodeChallengeMethod.Plain,
      )
    },
    test("parse request without code_challenge_method (defaults to plain)") {
      val env = Env()
      val params = validParams(codeChallengeMethod = None)

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.codeChallengeMethod == CodeChallengeMethod.Plain,
      )
    },
    test("parse request without state parameter") {
      val env = Env()
      val params = validParams(state = None)

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.state == None,
      )
    },
    test("parse request with 'code id_token' response type") {
      val env = Env()
      val params = validParams(responseType = "code id_token")

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.responseType == NonEmptySet(ResponseTypeEntry.Code, ResponseTypeEntry.IdToken),
      )
    },
  )

  def redirectUriValidationTests = suite("redirect_uri validation")(
    test("fail when redirect_uri is missing") {
      val env = Env()
      val params = validParams() - "redirect_uri"

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.RedirectUriMissingOrInvalid),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when redirect_uri is relative") {
      val env = Env()
      val params = validParams(redirectUri = relativeRedirectUri)

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.RedirectUriMissingOrInvalid),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when redirect_uri contains fragment") {
      val env = Env()
      val params = validParams(redirectUri = invalidRedirectUri)

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.RedirectUriMissingOrInvalid),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when redirect_uri is not registered for client") {
      val env = Env()
      val params = validParams(redirectUri = unregisteredRedirectUri)

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.RedirectUriIsNotRegistered(unregisteredRedirectUri)),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
  )

  def clientValidationTests = suite("client_id validation")(
    test("fail when client_id is missing") {
      val env = Env()
      val params = validParams() - "client_id"

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.ClientIdMissing),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when client_id is unknown") {
      val env = Env()
      val params = validParams(clientId = unknownClientId)

      for
        _ <- env.oauthClientService.find.succeedsWith(None)
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.UknownClientId(ClientId(unknownClientId))),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
  )

  def responseTypeValidationTests = suite("response_type validation")(
    test("fail when response_type is missing") {
      val env = Env()
      val params = validParams() - "response_type"

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.ResponseTypeMissing),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when response_type is unsupported") {
      val env = Env()
      val params = validParams(responseType = "token")

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.UnsupportedResponseType("token")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
  )

  def codeChallengeValidationTests = suite("code_challenge validation")(
    test("fail when code_challenge is missing") {
      val env = Env()
      val params = validParams() - "code_challenge"

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.CodeChallengeMissing),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when code_challenge is invalid (too short)") {
      val env = Env()
      val params = validParams(codeChallenge = invalidCodeChallenge)

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.CodeChallengeInvalid(invalidCodeChallenge)),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when code_challenge_method is invalid") {
      val env = Env()
      val params = validParams(codeChallengeMethod = Some("invalid"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.CodeChallengeMethodInvalid("invalid")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
  )

  def scopeValidationTests = suite("scope validation")(
    test("fail when scope is missing") {
      val env = Env()
      val params = validParams() - "scope"

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.ScopeMissing),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("parse single scope") {
      val env = Env()
      val params = validParams(scope = "read")

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.scope == Set(ScopeToken("read")),
      )
    },
    test("parse multiple scopes") {
      val env = Env()
      val params = validParams(scope = "read,write,admin")

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params)
      yield assertTrue(
        result.scope == Set(ScopeToken("read"), ScopeToken("write"), ScopeToken("admin")),
      )
    },
  )

  def multipleValuesTests = suite("multiple values rejection")(
    test("fail when client_id has multiple values") {
      val env = Env()
      val params = validParams() + ("client_id" -> Chunk("client1", "client2"))

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("client_id")),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when redirect_uri has multiple values") {
      val env = Env()
      val params = validParams() + ("redirect_uri" -> Chunk("uri1", "uri2"))

      for
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.RedirectUriMissingOrInvalid),
        result.left.map(_.redirectUri) == Left(None),
      )
    },
    test("fail when response_type has multiple values") {
      val env = Env()
      val params = validParams() + ("response_type" -> Chunk("code", "token"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("response_type")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when code_challenge has multiple values") {
      val env = Env()
      val params = validParams() + ("code_challenge" -> Chunk("challenge1", "challenge2"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("code_challenge")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when code_challenge_method has multiple values") {
      val env = Env()
      val params = validParams() + ("code_challenge_method" -> Chunk("S256", "plain"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("code_challenge_method")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when scope has multiple values") {
      val env = Env()
      val params = validParams() + ("scope" -> Chunk("read", "write"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("scope")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
    test("fail when state has multiple values") {
      val env = Env()
      val params = validParams() + ("state" -> Chunk("state1", "state2"))

      for
        _ <- env.oauthClientService.find.succeedsWith(Some(testClient))
        result <- env.parser.parse(params).either
      yield assertTrue(
        result.isLeft,
        result.left.map(_.error) == Left(Error.MultipleValuesProvided("state")),
        result.left.map(_.redirectUri.map(_.toString)) == Left(Some(validRedirectUri)),
      )
    },
  )


