package versola.oauth.authorize.model

import versola.oauth.model.State
import zio.http.URL

private[authorize] sealed trait Error extends Exception

private[authorize] object Error:
  case object BadRequest extends Error:
    val description = "Either client_id or redirect_uri is somehow missing, invalid, provided multiple times or not registered"

  sealed trait RedirectError(
      val error: ErrorCode,
      val errorDescription: String,
      val errorUri: Option[String],
  ) extends Error:
    def uri: URL
    def state: Option[State]

    def redirectUriWithErrorParams: URL =
      uri.addQueryParams(
        Iterable(
          "error" -> error,
          "error_description" -> errorDescription,
        )
          ++ errorUri.map("error_uri" -> _)
          ++ state.map("state" -> _),
      )

  case class MultipleValuesProvided(uri: URL, state: Option[State], queryParamName: String) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Parameter is included more than once - $queryParamName",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case class NoValuesProvided(uri: URL, state: Option[State], queryParamName: String) extends RedirectError(
    error = ErrorCode.InvalidRequest,
    errorDescription = s"At least one value should be provided - $queryParamName",
    errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
  )

  /** The `state` value itself is deliberately not echoed back here: it is invalid because it
    * is too long, and echoing it would grow the redirect URI further (and could push a
    * later ConversationCookie past the ~4 KiB per-cookie limit).
    */
  case class StateInvalid(uri: URL) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The state parameter exceeds the maximum allowed length of 128 characters",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    ):
    val state: Option[State] = None

  case class ResponseTypeMissing(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - response_type",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case class CodeChallengeMissing(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - code_challenge",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class CodeChallengeMethodMissing(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - code_challenge_method",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class CodeChallengeInvalid(uri: URL, state: Option[State], value: String) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Invalid code challenge alphabet or size - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc7636#section-4.3"),
    )

  case class CodeChallengeMethodInvalid(uri: URL, state: Option[State], value: String) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Code challenge method is not supported - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class UnsupportedResponseType(uri: URL, state: Option[State], responseType: String) extends RedirectError(
      error = ErrorCode.UnsupportedResponseType,
      errorDescription = s"Unsupported response type - $responseType",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case class ScopeMissing(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidScope,
      errorDescription = "Missing required parameter - scope",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case class InvalidClaims(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Invalid claims parameter - must be valid JSON",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#ClaimsParameter"),
    )

  case class UnsupportedUiLocales(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "None of the requested ui_locales are supported",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class AuthFlowMissing(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Client is not configured for sign-in - missing auth flow",
      errorUri = None,
    )

  case class LoginRequired(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.LoginRequired,
      errorDescription = "Authentication is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class ConsentRequired(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.ConsentRequired,
      errorDescription = "Consent is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class InteractionRequired(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InteractionRequired,
      errorDescription = "End-user interaction is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class AccessDenied(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.AccessDenied,
      errorDescription = "The resource owner could not be resolved for the existing session",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case class UnmetAuthenticationRequirements(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.UnmetAuthenticationRequirements,
      errorDescription = "The requested Authentication Context Class cannot be satisfied",
      errorUri = Some("https://openid.net/specs/openid-connect-unmet-authentication-requirements-1_0.html"),
    )

  case class PromptInvalid(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Invalid prompt parameter - none must not be combined with other values",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )
  case class IdTokenHintInvalid(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The id_token_hint could not be verified or is invalid (invalid signature, audience, or issuer)",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class ConflictingHints(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "login_hint and id_token_hint must not be used together",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class LoginHintInvalid(uri: URL, state: Option[State]) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The login_hint parameter is invalid or not supported by the client auth flow",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class InvalidTarget(uri: URL, state: Option[State], value: String) extends RedirectError(
      error = ErrorCode.InvalidTarget,
      errorDescription = s"The requested resource is invalid, malformed, or unknown - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc8707#section-2"),
    )

  case class InvalidAuthorizationDetails(uri: URL, state: Option[State], value: String) extends RedirectError(
      error = ErrorCode.InvalidAuthorizationDetails,
      errorDescription = s"The authorization_details parameter is invalid, malformed, or unknown - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9396#section-5"),
    )
