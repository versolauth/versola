/**
 * Validation utilities for Versola Central UI
 */
import type { MutualTlsAuth, OAuthClient } from '../types';

/**
 * Validates resource/action format: lowercase letters, numbers, underscore, starting with letter
 * Examples: users, user_profile, api_v2
 */
export function validateResourceAction(value: string): boolean {
  return /^[a-z][a-z0-9_]*$/.test(value);
}

/**
 * Validates permission format: lowercase segments separated by dots or colons
 * Each segment must start with a letter and may include numbers/underscores
 * Examples: users.read, users:read, users:read:managed
 */
export function validatePermission(permission: string): boolean {
  return /^[a-z][a-z0-9_]*([.:][a-z][a-z0-9_]*)*$/.test(permission);
}

/**
 * Validates role ID format: lowercase letters, numbers, hyphen, starting with letter
 * (unlike scope/permission/detail-type IDs, role IDs allow hyphens - matching built-in
 * roles like `oauth-admin` and `frontend-developer`)
 * Examples: admin, oauth-admin, frontend-developer
 */
export function validateRoleId(roleId: string): boolean {
  return /^[a-z][a-z0-9-]*$/.test(roleId);
}

/**
 * Validates scope ID format: same as resource/action
 * Lowercase letters, numbers, underscore, starting with letter
 * Examples: openid, profile, user_data
 */
export function validateScopeId(scopeId: string): boolean {
  return validateResourceAction(scopeId);
}

/**
 * Validates an RFC 9396 authorization detail type: same as resource/action
 * Lowercase letters, numbers, underscore, starting with letter
 * Examples: payment_initiation, account_information
 */
export function validateAuthorizationDetailType(type: string): boolean {
  return validateResourceAction(type);
}

/**
 * Validates client ID format: lowercase letters, numbers, hyphen, starting with letter
 * Examples: web-app, mobile-client, admin-dashboard-v2
 */
export function validateClientId(clientId: string): boolean {
  return /^[a-z][a-z0-9-]*$/.test(clientId);
}

/**
 * Validates tenant ID format: same as client ID
 * Lowercase letters, numbers, hyphen, starting with letter
 * Examples: acme-prod, internal-admin, team-alpha-2
 */
export function validateTenantId(tenantId: string): boolean {
  return validateClientId(tenantId);
}

/**
 * Validates edge ID format: must start with "edge-" followed by lowercase letters, numbers, hyphen
 * Examples: edge-us-east-1, edge-prod, edge-eu-west-2
 */
export function validateEdgeId(edgeId: string): boolean {
  return /^edge-[a-z0-9-]+$/.test(edgeId);
}

/**
 * Validates redirect URI according to OAuth 2.1 and RFC 6749
 * - Must be absolute URI
 * - Must not contain fragment (#)
 * - Must use https:// (or http://localhost for dev)
 * - Custom schemes allowed for native apps (com.example.app://)
 */
export function validateRedirectUri(uri: string): { valid: boolean; error?: string } {
  if (!uri || uri.trim() === '') {
    return { valid: false, error: 'URI cannot be empty' };
  }

  // Check for fragment
  if (uri.includes('#')) {
    return { valid: false, error: 'URI must not contain fragment (#)' };
  }

  // Check if it's a valid URI format
  try {
    const url = new URL(uri);
    
    // Allow https, http (only for localhost), and custom schemes
    if (url.protocol === 'https:') {
      return { valid: true };
    }
    
    if (url.protocol === 'http:') {
      // Only allow http for localhost/127.0.0.1
      if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
        return { valid: true };
      }
      return { valid: false, error: 'HTTP only allowed for localhost' };
    }
    
    // Allow custom schemes (for native apps like com.example.app://)
    if (url.protocol && url.protocol.endsWith(':')) {
      return { valid: true };
    }
    
    return { valid: false, error: 'Invalid protocol (use https:// or custom scheme)' };
  } catch (e) {
    return { valid: false, error: 'Invalid URI format' };
  }
}

/**
 * Validates a logout notification URI (front-channel and back-channel)
 * Unlike redirect URIs, custom schemes are not allowed: front-channel URIs are
 * loaded in an iframe and back-channel URIs are called server-to-server, so only
 * https:// (or http://localhost for dev) is accepted.
 */
export function validateLogoutUri(uri: string): { valid: boolean; error?: string } {
  if (!uri || uri.trim() === '') {
    return { valid: false, error: 'URI cannot be empty' };
  }

  if (uri.includes('#')) {
    return { valid: false, error: 'URI must not contain fragment (#)' };
  }

  try {
    const url = new URL(uri);

    if (url.protocol === 'https:') {
      return { valid: true };
    }

    if (url.protocol === 'http:') {
      if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
        return { valid: true };
      }
      return { valid: false, error: 'HTTP only allowed for localhost' };
    }

    return { valid: false, error: 'Logout URI must use https://' };
  } catch (e) {
    return { valid: false, error: 'Invalid URI format' };
  }
}

/** Consent-screen links are opened or loaded by a browser, so only absolute HTTPS
 * URLs are accepted. Native-app custom schemes are not valid here. */
export function validateConsentUri(uri: string): { valid: boolean; error?: string } {
  const trimmed = uri.trim();
  if (!trimmed) return { valid: true };

  try {
    const url = new URL(trimmed);
    if (url.protocol !== 'https:' || !url.hostname) {
      return { valid: false, error: 'Consent URI must use an absolute https:// URL' };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: 'Invalid consent URI format' };
  }
}

/**
 * Validates resource URI according to backend ResourceUri rules
 * - Must be absolute URI
 * - Must not contain a path
 * - Must not contain query parameters
 * - Must not contain fragment (#)
 */
export function validateResourceUri(uri: string): { valid: boolean; error?: string } {
  const trimmed = uri.trim();
  if (!trimmed) {
    return { valid: false, error: 'Resource URI cannot be empty' };
  }

  try {
    const url = new URL(trimmed);
    if (url.protocol.toLowerCase() === 'resource:') {
      return { valid: false, error: 'Resource URI scheme resource:// is reserved' };
    }
    if (url.search) {
      return { valid: false, error: 'Resource URI query must be empty' };
    }
    if (url.hash) {
      return { valid: false, error: 'Resource URI fragment must be empty' };
    }
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/?#]+$/.test(trimmed)) {
      return { valid: false, error: 'Resource URI path must be empty' };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: 'Resource URI must be absolute' };
  }
}

/**
 * Validates a resource ID. IDs are also used in resource://<id> internal-resource URIs.
 */
export function validateResourceId(resourceId: string): { valid: boolean; error?: string } {
  const trimmed = resourceId.trim();
  if (!/^[a-z][a-z0-9-]*$/.test(trimmed)) {
    return { valid: false, error: 'Resource ID must start with a lowercase Latin letter and contain only lowercase Latin letters, numbers, and hyphens' };
  }
  if (trimmed === 'edge') {
    return { valid: false, error: 'Resource ID "edge" is reserved' };
  }
  return { valid: true };
}

/**
 * Convert TTL value and unit to seconds
 */
export function ttlToSeconds(value: number, unit: 'minutes' | 'hours'): number {
  return unit === 'hours' ? value * 3600 : value * 60;
}

/**
 * Convert seconds to TTL value and unit (prefer hours if evenly divisible)
 */
export function secondsToTtl(seconds: number): { value: number; unit: 'minutes' | 'hours' } {
  if (seconds % 3600 === 0 && seconds >= 3600) {
    return { value: seconds / 3600, unit: 'hours' };
  }
  return { value: seconds / 60, unit: 'minutes' };
}

export function daysToSeconds(days: number): number {
  return Math.round(days * 24 * 60 * 60);
}

export function secondsToDays(seconds: number): number {
  return Math.max(1, Math.round(seconds / (24 * 60 * 60)));
}

/** Kept in sync with `InvalidRegistrationConfiguration` on the backend, which is the
  * authoritative check - this only lets the admin catch the mistake before submitting. */
export const MIN_DPOP_BOUND_ACCESS_TOKEN_TTL_SECONDS = 3600;
export const MAX_ACCESS_TOKEN_TTL_SECONDS = 86400;
export const MIN_DPOP_RSA_KEY_SIZE = 2048;

/** The DPoP signing algorithms the backend knows how to verify a proof with. */
export const DPOP_SIGNING_ALGS = ['ES256', 'PS256', 'RS256'] as const;

/**
 * Validates a client's minimum RSA DPoP proof key size. The floor is RFC 7518 section 3.3 and
 * auth applies it whether or not a client registered anything, so a lower value here would be
 * a number the server ignores rather than a policy.
 */
export function validateDpopMinRsaKeySize(bits: number | null | undefined): { valid: boolean; error?: string } {
  if (bits === null || bits === undefined) {
    return { valid: true };
  }
  if (!Number.isInteger(bits) || bits < MIN_DPOP_RSA_KEY_SIZE) {
    return { valid: false, error: `Must be at least ${MIN_DPOP_RSA_KEY_SIZE} bits` };
  }
  return { valid: true };
}

/**
 * Validates access token TTL. A DPoP-bound access token has no bearer-replay risk without
 * the private key, so it may live long enough to be worth the write-load savings - but every
 * access token, bound or not, is capped so a leaked deny-list entry or a stale permission
 * grant cannot outlive it by more than a day.
 */
export function validateAccessTokenTtl(seconds: number, dpopBoundAccessTokens: boolean): { valid: boolean; error?: string } {
  if (dpopBoundAccessTokens && seconds < MIN_DPOP_BOUND_ACCESS_TOKEN_TTL_SECONDS) {
    return { valid: false, error: 'Must be at least 1 hour when DPoP-bound access tokens are required' };
  }
  if (seconds > MAX_ACCESS_TOKEN_TTL_SECONDS) {
    return { valid: false, error: 'Must not exceed 24 hours' };
  }
  return { valid: true };
}

/** RFC 8705 section 2.1.2 subject types a client's certificate can be recognised by. */
export const MTLS_SUBJECT_TYPES = ['subject_dn', 'san_dns', 'san_uri', 'san_ip', 'san_email'] as const;

/** Kept in sync with `JsonWebKeySet.MaxKeys` on the backend. */
export const MAX_JWKS_KEYS = 10;

/**
 * Parses and shallow-validates a pasted JWK Set document. Deeper checks the backend also
 * applies - key type, usability with a supported algorithm, no private key material - are
 * left to it; this only catches what's worth surfacing before a submit round-trip.
 */
export function validateJwksJson(raw: string): { valid: boolean; error?: string; keySet?: Record<string, unknown> } {
  const trimmed = raw.trim();
  if (!trimmed) {
    return { valid: false, error: 'A JWK Set is required' };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { valid: false, error: 'Must be valid JSON' };
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { valid: false, error: 'Must be a JWK Set object, e.g. {"keys": [...]}' };
  }
  const keys = (parsed as Record<string, unknown>).keys;
  if (!Array.isArray(keys) || keys.length === 0) {
    return { valid: false, error: 'Must contain at least one key under "keys"' };
  }
  if (keys.length > MAX_JWKS_KEYS) {
    return { valid: false, error: `Must not contain more than ${MAX_JWKS_KEYS} keys` };
  }
  return { valid: true, keySet: parsed as Record<string, unknown> };
}

/**
 * Kept in sync with `InvalidRegistrationConfiguration.validateClientAuthentication` - a client
 * authenticates one way, so mtlsAuth and jwks combine in exactly one direction: tls_client_auth
 * refuses jwks (two credentials for one client), self_signed_tls_client_auth requires it (the
 * registered keys are what the certificate is matched against).
 */
export function validateClientCredential(
  mtlsAuth: MutualTlsAuth | null | undefined,
  hasJwks: boolean,
): { valid: boolean; error?: string } {
  if (mtlsAuth?.type === 'tls_client_auth' && hasJwks) {
    return { valid: false, error: 'A client authenticates either with mtlsAuth or with jwks, not both' };
  }
  if (mtlsAuth?.type === 'self_signed_tls_client_auth' && !hasJwks) {
    return { valid: false, error: 'self_signed_tls_client_auth needs a registered JWK Set to match the certificate against' };
  }
  return { valid: true };
}

/** Kept in sync with `InvalidRegistrationConfiguration.validateRequestObjectRequirement`. */
export function validateRequestObjectRequirement(
  requireSignedRequestObject: boolean,
  hasJwks: boolean,
): { valid: boolean; error?: string } {
  if (requireSignedRequestObject && !hasJwks) {
    return { valid: false, error: 'Requires a registered JWK Set - a request object is verified against no other keys' };
  }
  return { valid: true };
}

/**
 * Kept in sync with `InvalidRegistrationConfiguration.validateMtlsTermination` - `auth` reads
 * a certificate only from the header the client's own tenant names, so registering mtlsAuth
 * under a tenant that names none is refused by the backend outright, not merely left unable
 * to authenticate.
 */
export function validateMtlsTermination(
  mtlsAuth: MutualTlsAuth | null | undefined,
  mtlsCertificateHeader: string | null | undefined,
): { valid: boolean; error?: string } {
  if (mtlsAuth && !mtlsCertificateHeader) {
    return {
      valid: false,
      error: 'This tenant has no mTLS certificate header configured under Challenges & Security - '
        + 'Central refuses a client that registers an mTLS credential without one, '
        + 'since no certificate would ever reach auth for it.',
    };
  }
  return { valid: true };
}

/**
 * Whether every key in a set could verify a signature, which is what a request object is
 * checked against - kept in sync with `ClientAssertion.usableWith`: RS256 or PS256 for an RSA
 * key, ES256 for an EC key, which names P-256 as its curve, and a key that pinned its own
 * `alg` is usable only with that one.
 *
 * A `self_signed_tls_client_auth` set is held to nothing of the sort on the backend - §2.2
 * matches encoded public key material, so a P-384 key registers perfectly well. Requiring
 * signed request objects from such a client registers one whose every authorization request
 * is refused, since no key in the set can verify the object.
 */
export function jwksVerifiesRequestObjects(keySet: Record<string, unknown> | null | undefined): boolean {
  const keys = keySet?.keys;
  if (!Array.isArray(keys) || keys.length === 0) {
    return false;
  }
  return keys.every(key => {
    if (typeof key !== 'object' || key === null) {
      return false;
    }
    const { kty, crv, alg, use } = key as Record<string, unknown>;
    if (use === 'enc') {
      return false;
    }
    if (kty === 'RSA') {
      return alg === undefined || alg === 'RS256' || alg === 'PS256';
    }
    if (kty === 'EC') {
      return crv === 'P-256' && (alg === undefined || alg === 'ES256');
    }
    return false;
  });
}

/** What a registered client actually authenticates with at the token endpoint. */
export type ClientCredentialKind = 'secret' | 'public' | 'mtls' | 'private_key_jwt';

/**
 * Kept in sync with `ClientAuthentication`: a client that registered an mTLS credential or a
 * key set authenticates with that and only that - the secret Central still generates for it
 * is refused at the token endpoint, so nothing should present it as the client's credential.
 */
export function clientCredentialKind(
  client: Pick<OAuthClient, 'mtlsAuth' | 'jwks' | 'clientType'>,
): ClientCredentialKind {
  if (client.mtlsAuth) {
    return 'mtls';
  }
  if (client.jwks) {
    return 'private_key_jwt';
  }
  return client.clientType === 'native' ? 'public' : 'secret';
}

/**
 * Get error message for validation type
 */
export function getValidationError(type: string): string {
  const errors: Record<string, string> = {
    permission: 'Lowercase letters, numbers, underscore, dot or colon separators, start each segment with letter',
    role: 'Lowercase letters, numbers, hyphen, start with letter',
    scope: 'Lowercase letters, numbers, underscore, start with letter',
    clientId: 'Lowercase letters, numbers, hyphen, start with letter',
    tenantId: 'Lowercase letters, numbers, hyphen, start with letter',
    audience: 'Lowercase letters, numbers, hyphen, start with letter',
  };
  return errors[type] || 'Invalid format';
}

