package versola.oauth.authorize.model

import versola.oauth.authorize.AuthorizeRedirect
import versola.oauth.client.model.ClientId
import versola.oauth.model.State
import zio.http.URL

private[authorize] sealed trait Error extends Exception

private[authorize] object Error:
  case object BadRequest extends Error:
    val description = "Either client_id or redirect_uri is somehow missing, invalid, provided multiple times or not registered"

  /** RFC 9101 §6.2/§6.3: the `request` parameter is not an object this client signed, or does
    * not carry the request it claims to.
    *
    * Answered directly rather than redirected, unlike the parameter errors below: the only
    * `redirect_uri` a request object request carries is the one inside the object, and an
    * object that failed verification is exactly the one whose contents cannot be trusted to
    * name where a response may be sent. Why it failed is logged rather than returned, for the
    * same reason a failed client authentication does not say which check it failed.
    */
  case object InvalidRequestObject extends Error:
    val error: ErrorCode = ErrorCode.InvalidRequestObject
    val description = "The request parameter does not contain a valid Request Object for this client"

  sealed trait RedirectError(
      val error: ErrorCode,
      val errorDescription: String,
      val errorUri: Option[String],
  ) extends Error:
    /** The client the error is returned to. Under JARM the response is signed with that
      * client's tenant key, so the error has to name it as a successful response does. */
    def clientId: ClientId
    def uri: URL
    def state: Option[State]
    def responseMode: ResponseMode

    /** The parameters of the error response, which under JARM become the claims of the signed
      * `response` JWT instead of query or fragment parameters.
      */
    def errorParams(iss: String): List[(String, String)] =
      List(
        "error" -> error.toString,
        "error_description" -> errorDescription,
        "iss" -> iss,
      )
        ++ errorUri.map("error_uri" -> _)
        ++ state.map("state" -> _)

    /** The redirect for an error returned under a plain response mode. A JARM error is built
      * by `AuthorizationResponseService`, which can sign it.
      */
    def redirectUriWithErrorParams(iss: String): URL =
      AuthorizeRedirect.responseUrl(uri, errorParams(iss), responseMode)


  case class MultipleValuesProvided(clientId: ClientId, uri: URL, state: Option[State], queryParamName: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Parameter is included more than once - $queryParamName",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case class NoValuesProvided(clientId: ClientId, uri: URL, state: Option[State], queryParamName: String, responseMode: ResponseMode) extends RedirectError(
    error = ErrorCode.InvalidRequest,
    errorDescription = s"At least one value should be provided - $queryParamName",
    errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
  )

  /** The `state` value itself is deliberately not echoed back here: it is invalid because it
    * is too long, and echoing it would grow the redirect URI further (and could push a
    * later ConversationCookie past the ~4 KiB per-cookie limit).
    */
  case class StateInvalid(clientId: ClientId, uri: URL, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The state parameter exceeds the maximum allowed length of 128 characters",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    ):
    val state: Option[State] = None

  case class ResponseTypeMissing(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - response_type",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case class CodeChallengeMissing(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - code_challenge",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class CodeChallengeMethodMissing(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - code_challenge_method",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class CodeChallengeInvalid(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Invalid code challenge alphabet or size - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc7636#section-4.3"),
    )

  case class CodeChallengeMethodInvalid(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Code challenge method is not supported - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case class UnsupportedResponseType(clientId: ClientId, uri: URL, state: Option[State], responseType: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.UnsupportedResponseType,
      errorDescription = s"Unsupported response type - $responseType",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  /** Covers a value this server does not implement and one the response type forbids alike:
    * both leave no mode the response could be returned in, so the error itself goes back in
    * the mode the response type implies rather than the one that was asked for.
    */
  case class ResponseModeInvalid(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Unsupported or inapplicable response mode - $value",
      errorUri = Some("https://openid.net/specs/oauth-v2-multiple-response-types-1_0.html#ResponseModes"),
    )

  case class ScopeMissing(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidScope,
      errorDescription = "Missing required parameter - scope",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  /** Only the scopes the client is not registered for are named back, not the whole requested
    * set: the unregistered ones are the reason the request failed, and echoing the rest would
    * grow the redirect URI for no benefit.
    */
  case class ScopeNotGranted(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidScope,
      errorDescription = s"The requested scope is not registered for this client - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-3.3"),
    )


  case class InvalidClaims(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Invalid claims parameter - must be valid JSON",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#ClaimsParameter"),
    )

  case class UnsupportedUiLocales(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "None of the requested ui_locales are supported",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class AuthFlowMissing(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Client is not configured for sign-in - missing auth flow",
      errorUri = None,
    )

  case class LoginRequired(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.LoginRequired,
      errorDescription = "Authentication is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class ConsentRequired(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.ConsentRequired,
      errorDescription = "Consent is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class InteractionRequired(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InteractionRequired,
      errorDescription = "End-user interaction is required but prompt=none was requested",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class AccessDenied(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.AccessDenied,
      errorDescription = "The resource owner could not be resolved for the existing session",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case class UnmetAuthenticationRequirements(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.UnmetAuthenticationRequirements,
      errorDescription = "The requested Authentication Context Class cannot be satisfied",
      errorUri = Some("https://openid.net/specs/openid-connect-unmet-authentication-requirements-1_0.html"),
    )

  case class PromptInvalid(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Invalid prompt parameter - none must not be combined with other values",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )
  case class IdTokenHintInvalid(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The id_token_hint could not be verified or is invalid (invalid signature, audience, or issuer)",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class ConflictingHints(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "login_hint and id_token_hint must not be used together",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class LoginHintInvalid(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The login_hint parameter is invalid or not supported by the client auth flow",
      errorUri = Some("https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest"),
    )

  case class InvalidTarget(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidTarget,
      errorDescription = s"The requested resource is invalid, malformed, or unknown - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc8707#section-2"),
    )

  /** RFC 9449 §10: the value is refused here rather than carried into the code, because a
    * thumbprint no proof could ever produce would otherwise only surface at redemption -- by
    * which point the code is spent and the client cannot be told what was wrong with it. */
  case class DpopJktInvalid(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "The dpop_jkt parameter is not a base64url-encoded JWK thumbprint",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9449#section-10"),
    )

  case class InvalidAuthorizationDetails(clientId: ClientId, uri: URL, state: Option[State], value: String, responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidAuthorizationDetails,
      errorDescription = s"The authorization_details parameter is invalid, malformed, or unknown - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9396#section-5"),
    )

  /** RFC 9101 §10.5: the client registered that it states its requests in a signed object,
    * and this one is a plain parameter set.
    *
    * Redirected rather than answered directly, unlike [[InvalidRequestObject]]: there is no
    * object here whose contents could be in doubt, and the `redirect_uri` reached this point
    * only by matching one the client registered.
    */
  case class SignedRequestObjectRequired(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "This client must state its authorization request in a signed request object",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9101#section-10.5"),
    )

  /** RFC 9126 §6.2: the client registered that it pushes its requests, and this one arrived
    * at `/authorize` without a `request_uri`. */
  case class PushedAuthorizationRequired(clientId: ClientId, uri: URL, state: Option[State], responseMode: ResponseMode) extends RedirectError(
      error = ErrorCode.InvalidRequest,
      errorDescription = "This client must push its authorization request to the pushed authorization request endpoint",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc9126#section-6.2"),
    )
