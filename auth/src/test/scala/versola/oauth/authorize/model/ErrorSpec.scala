package versola.oauth.authorize.model

import versola.oauth.client.model.ClientId
import versola.oauth.model.State
import zio.test.*

object ErrorSpec extends ZIOSpecDefault:

  private val clientId = ClientId("test-client")
  private val uri = zio.http.URL.decode("https://example.com/callback").toOption.get
  private val state = Some(State("test-state"))

  /** Every redirect error is reported to the client through the same query-parameter
    * encoding, so the assertions below only vary the error code and description.
    */
  private def check(name: String, error: Error.RedirectError, code: ErrorCode, description: String) =
    test(name) {
      val redirect = error.redirectUriWithErrorParams("https://issuer.example")
      assertTrue(
        error.error == code,
        error.errorDescription == description,
        redirect.queryParams.queryParam("error") == Some(code.toString),
        redirect.queryParams.queryParam("error_description") == Some(description),
        redirect.queryParams.queryParam("iss") == Some("https://issuer.example"),
      )
    }

  def spec = suite("authorize Error")(
    suite("prompt=none rejections")(
      check(
        "LoginRequired",
        Error.LoginRequired(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.LoginRequired,
        "Authentication is required but prompt=none was requested",
      ),
      check(
        "InteractionRequired",
        Error.InteractionRequired(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InteractionRequired,
        "End-user interaction is required but prompt=none was requested",
      ),
      check(
        "PromptInvalid",
        Error.PromptInvalid(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "Invalid prompt parameter - none must not be combined with other values",
      ),
    ),
    suite("hint rejections")(
      check(
        "IdTokenHintInvalid",
        Error.IdTokenHintInvalid(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "The id_token_hint could not be verified or is invalid (invalid signature, audience, or issuer)",
      ),
      check(
        "ConflictingHints",
        Error.ConflictingHints(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "login_hint and id_token_hint must not be used together",
      ),
      check(
        "LoginHintInvalid",
        Error.LoginHintInvalid(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "The login_hint parameter is invalid or not supported by the client auth flow",
      ),
    ),
    suite("request rejections")(
      check(
        "CodeChallengeMissing",
        Error.CodeChallengeMissing(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "Missing required parameter - code_challenge",
      ),
      check(
        "CodeChallengeInvalid",
        Error.CodeChallengeInvalid(clientId, uri, state, "short", responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "Invalid code challenge alphabet or size - short",
      ),
      check(
        "InvalidClaims",
        Error.InvalidClaims(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "Invalid claims parameter - must be valid JSON",
      ),
      check(
        "UnsupportedUiLocales",
        Error.UnsupportedUiLocales(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "None of the requested ui_locales are supported",
      ),
      check(
        "UnmetAuthenticationRequirements",
        Error.UnmetAuthenticationRequirements(clientId, uri, state, responseMode = ResponseMode.Query),
        ErrorCode.UnmetAuthenticationRequirements,
        "The requested Authentication Context Class cannot be satisfied",
      ),
    ),
    suite("parameter arity")(
      check(
        "MultipleValuesProvided names the offending parameter",
        Error.MultipleValuesProvided(clientId, uri, state, "scope", responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "Parameter is included more than once - scope",
      ),
      check(
        "NoValuesProvided names the offending parameter",
        Error.NoValuesProvided(clientId, uri, state, "scope", responseMode = ResponseMode.Query),
        ErrorCode.InvalidRequest,
        "At least one value should be provided - scope",
      ),
    ),
    test("the redirect keeps query parameters the client already put on its redirect_uri") {
      val withQuery = zio.http.URL.decode("https://example.com/callback?tenant=acme").toOption.get
      val redirect = Error.LoginRequired(clientId, withQuery, state, responseMode = ResponseMode.Query).redirectUriWithErrorParams("https://issuer.example")
      assertTrue(
        redirect.queryParams.queryParam("tenant") == Some("acme"),
        redirect.queryParams.queryParam("error") == Some(ErrorCode.LoginRequired.toString),
      )
    },
  )
