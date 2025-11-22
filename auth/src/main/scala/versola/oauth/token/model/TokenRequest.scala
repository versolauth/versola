package versola.oauth.token.model

import versola.oauth.model.{AuthorizationCode, ClientId, ClientSecret, CodeVerifier, GrantType}

/**
 * OAuth 2.0 Token Request
 * RFC 6749 Section 4.1.3: https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3
 * RFC 7636 (PKCE): https://datatracker.ietf.org/doc/html/rfc7636
 */
private[token] case class TokenRequest(
    grantType: GrantType,
    code: AuthorizationCode,
    redirectUri: String,
    clientId: ClientId,
    clientSecret: Option[ClientSecret],
    codeVerifier: CodeVerifier,
)

