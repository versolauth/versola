package versola.oauth.authorize.model

import versola.oauth.model.State
import zio.test.*

object ErrorSpec extends ZIOSpecDefault:

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
        Error.LoginRequired(uri, state, useFragment = false),
        ErrorCode.LoginRequired,
        "Authentication is required but prompt=none was requested",
      ),
      check(
        "InteractionRequired",
        Error.InteractionRequired(uri, state, useFragment = false),
        ErrorCode.InteractionRequired,
        "End-user interaction is required but prompt=none was requested",
      ),
      check(
        "PromptInvalid",
        Error.PromptInvalid(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "Invalid prompt parameter - none must not be combined with other values",
      ),
    ),
    suite("hint rejections")(
      check(
        "IdTokenHintInvalid",
        Error.IdTokenHintInvalid(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "The id_token_hint could not be verified or is invalid (invalid signature, audience, or issuer)",
      ),
      check(
        "ConflictingHints",
        Error.ConflictingHints(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "login_hint and id_token_hint must not be used together",
      ),
      check(
        "LoginHintInvalid",
        Error.LoginHintInvalid(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "The login_hint parameter is invalid or not supported by the client auth flow",
      ),
    ),
    suite("request rejections")(
      check(
        "CodeChallengeMissing",
        Error.CodeChallengeMissing(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "Missing required parameter - code_challenge",
      ),
      check(
        "CodeChallengeInvalid",
        Error.CodeChallengeInvalid(uri, state, "short", useFragment = false),
        ErrorCode.InvalidRequest,
        "Invalid code challenge alphabet or size - short",
      ),
      check(
        "InvalidClaims",
        Error.InvalidClaims(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "Invalid claims parameter - must be valid JSON",
      ),
      check(
        "UnsupportedUiLocales",
        Error.UnsupportedUiLocales(uri, state, useFragment = false),
        ErrorCode.InvalidRequest,
        "None of the requested ui_locales are supported",
      ),
      check(
        "UnmetAuthenticationRequirements",
        Error.UnmetAuthenticationRequirements(uri, state, useFragment = false),
        ErrorCode.UnmetAuthenticationRequirements,
        "The requested Authentication Context Class cannot be satisfied",
      ),
    ),
    suite("parameter arity")(
      check(
        "MultipleValuesProvided names the offending parameter",
        Error.MultipleValuesProvided(uri, state, "scope", useFragment = false),
        ErrorCode.InvalidRequest,
        "Parameter is included more than once - scope",
      ),
      check(
        "NoValuesProvided names the offending parameter",
        Error.NoValuesProvided(uri, state, "scope", useFragment = false),
        ErrorCode.InvalidRequest,
        "At least one value should be provided - scope",
      ),
    ),
    test("the redirect keeps query parameters the client already put on its redirect_uri") {
      val withQuery = zio.http.URL.decode("https://example.com/callback?tenant=acme").toOption.get
      val redirect = Error.LoginRequired(withQuery, state, useFragment = false).redirectUriWithErrorParams("https://issuer.example")
      assertTrue(
        redirect.queryParams.queryParam("tenant") == Some("acme"),
        redirect.queryParams.queryParam("error") == Some(ErrorCode.LoginRequired.toString),
      )
    },
  )
