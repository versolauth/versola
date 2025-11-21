package versola.oauth.authorize.model

import versola.oauth.model.ClientId

private[authorize] enum Error(
    val error: String,
    val errorDescription: String,
    val errorUri: Option[String],
):

  case MultipleValuesProvided(queryParamName: String) extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Parameter is included more than once - $queryParamName",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case ResponseTypeMissing extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - response_type",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case ClientIdMissing extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - client_id",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case CodeChallengeMissing extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing required parameter - code_challenge",
      errorUri = Some("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1#name-authorization-request"),
    )

  case CodeChallengeInvalid(value: String) extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Invalid code challenge alphabet or size - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc7636#section-4.3"),
    )

  case CodeChallengeMethodInvalid(value: String) extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = s"Code challenge method is not supported - $value",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc7636#section-4.3"),
    )

  case UnsupportedResponseType(responseType: String) extends Error(
      error = ErrorCode.UnsupportedResponseType,
      errorDescription = s"Unsupported response type - $responseType",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case ScopeMissing extends Error(
      error = ErrorCode.InvalidScope,
      errorDescription = "Missing required parameter - scope",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1"),
    )

  case UknownClientId(clientId: ClientId) extends Error(
      error = ErrorCode.UnauthorizedClient,
      errorDescription = s"Unknown client - $clientId",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
    )

  case RedirectUriIsNotRegistered(redirectUri: String) extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Provided redirect_uri is not registered",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-3.1.2"),
    )

  case RedirectUriMissingOrInvalid extends Error(
      error = ErrorCode.InvalidRequest,
      errorDescription = "Missing or invalid required parameter - redirect_uri",
      errorUri = Some("https://datatracker.ietf.org/doc/html/rfc6749#section-3.1.2"),
    )
