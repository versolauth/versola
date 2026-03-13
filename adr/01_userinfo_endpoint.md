# ADR 01: UserInfo Endpoint Implementation

**Status:** Implemented
**Date:** 2026-03-11
**Updated:** 2026-03-13
**Author:** Georgii Kovalev
**Context:** OpenID Connect UserInfo Endpoint (OIDC Core 1.0 Section 5.3)

## Implementation Status

### ✅ Core Features (Implemented)

- [x] **UserInfo Endpoint** - GET/POST `/v1/userinfo` with Bearer token authentication
- [x] **JWT Access Token Validation** - Signature verification, expiration check, claims extraction
- [x] **Scope-Based Authorization** - Map scopes to claims (openid, profile, email, address, phone)
- [x] **Claims Parameter Support** - Fine-grained claim requests via `RequestedClaims`
- [x] **Localized Claims (BCP47)** - Support for `name#fr-CA`, `name#fr`, etc.
- [x] **UI Locales Parameter** - Stored in authorization codes, refresh tokens, JWT access tokens
- [x] **Locale Resolution Algorithm** - Exact match → language match → default fallback
- [x] **JSONB Claims Storage** - Native JSON types in PostgreSQL
- [x] **Error Handling (RFC 6750)** - `invalid_token`, `insufficient_scope` with WWW-Authenticate header
- [x] **Performance Optimization** - Cached scope mappings, single DB query for user claims

### 🔮 Future Enhancements (Planned - OIDC Core 1.0)

- [ ] **Aggregated Claims** - External claims as signed JWT (Section 5.6.2)
- [ ] **Distributed Claims** - Client-fetched external claims (Section 5.6.2)
- [ ] **Signed UserInfo Response** - Return UserInfo as JWT (Section 5.3.2)
- [ ] **Encrypted UserInfo Response** - JWE encryption for privacy (Section 5.3.2)
- [ ] **Essential Claims Enforcement** - Deny authorization if essential claims missing (Section 5.5.1)
- [ ] **Claim Value Filtering** - Match specific claim values (Section 5.5.1)

## Executive Summary

This ADR documents the design and implementation of the OpenID Connect UserInfo endpoint (`/v1/userinfo`), which returns claims about the authenticated end-user. The endpoint accepts JWT access tokens, validates them, and returns user claims based on the scopes and claims parameter from the original authorization request.

**Core Design:**
```
Input: JWT access token (Bearer token in Authorization header)
Validation: JWT signature verification + expiration check
Claims Resolution: Scopes → Claims mapping + claims parameter filtering
Storage: User claims stored as JSON (JSONB) in PostgreSQL
Output: JSON response with requested user claims
```

## 1. Context and Requirements

### 1.1 OpenID Connect UserInfo Endpoint

According to OpenID Connect Core 1.0 specification (Section 5.3):

- **Purpose:** Returns claims about the authenticated end-user
- **Authentication:** Requires valid access token (Bearer token)
- **Authorization:** Only returns claims authorized by the granted scopes
- **Protocol:** HTTP GET or POST with access token in Authorization header
- **Response:** JSON object containing user claims

### 1.2 Claims and Scopes

**Standard OpenID Connect Scopes:**
- `openid` - Required for OIDC, returns `sub` claim
- `profile` - Returns profile claims: `name`, `family_name`, `given_name`, `middle_name`, `nickname`, `preferred_username`, `profile`, `picture`, `website`, `gender`, `birthdate`, `zoneinfo`, `locale`, `updated_at`
- `email` - Returns `email`, `email_verified`
- `address` - Returns `address` (formatted postal address)
- `phone` - Returns `phone_number`, `phone_number_verified`

**Claims Parameter:**
The authorization request may include a `claims` parameter (JSON object) specifying individual claims requested for the UserInfo endpoint:
```json
{
  "userinfo": {
    "given_name": {"essential": true},
    "email": null,
    "picture": null
  }
}
```

### 1.3 Security Requirements

- **Token validation:** Must verify JWT signature
- **Expiration check:** Must reject expired tokens
- **Scope enforcement:** Only return claims authorized by granted scopes
- **Client authorization:** Verify token was issued to a valid client
- **Privacy:** Only expose claims explicitly requested and authorized

### 1.4 Performance Requirements

- **Low latency:** <50ms response time for cached user data
- **High throughput:** Support resource servers checking user info on each request
- **Database efficiency:** Single query to fetch user claims

## 2. Design Decision

### 2.1 Database Schema

Users table includes JSONB column for storing user claims:

```sql
CREATE TABLE users (
    id UUID NOT NULL PRIMARY KEY,
    email TEXT,
    phone TEXT,
    claims JSONB NOT NULL
);
```

**Claims Storage Format:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name": "John Doe",
  "given_name": "John",
  "family_name": "Doe",
  "middle_name": "Robert",
  "email": "john.doe@example.com",
  "email_verified": true,
  "phone_number": "+1234567890",
  "phone_number_verified": false,
  "picture": "https://example.com/avatar.jpg",
  "birthdate": "1990-01-15",
  "locale": "en-US",
  "zoneinfo": "America/New_York",
  "updated_at": 1678901234,
  "address": {
    "formatted": "123 Main St\nApt 4B\nNew York, NY 10001\nUSA",
    "street_address": "123 Main St, Apt 4B",
    "locality": "New York",
    "region": "NY",
    "postal_code": "10001",
    "country": "USA"
  }
}
```

**Storage Rules:**
- **Strings:** Stored as JSON strings (e.g., `"John Doe"`)
- **Booleans:** Stored as JSON booleans (e.g., `true`, `false`)
- **Numbers:** Stored as JSON numbers (e.g., `1678901234`)
- **Objects:** Stored as nested JSON objects (e.g., `address` claim)
- **Arrays:** Supported for future use (e.g., multiple email addresses)
- `sub` claim always equals user ID (UUID)
- Claims are optional (not all users have all claims)
- Empty claims object `{}` is valid
- JSONB preserves native JSON types for efficient querying and storage

### 2.2 Endpoint Specification

**URL:** `POST /v1/userinfo` or `GET /v1/userinfo`  
**Authentication:** Bearer token (access token) in `Authorization` header  
**Content-Type:** `application/json`

**Request:**
```http
GET /v1/userinfo HTTP/1.1
Host: auth.example.com
Authorization: Bearer <access_token>
```

**Success Response (200 OK):**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name": "John Doe",
  "email": "john.doe@example.com",
  "email_verified": true
}
```

**Error Responses:**
- `401 Unauthorized` - Invalid, expired, or missing token
- `403 Forbidden` - Token valid but insufficient scope
- `500 Internal Server Error` - Server error

### 2.3 Claims Resolution Algorithm

**Step 1: Extract granted scopes from JWT access token**
- Read from `scope` claim in the JWT

**Step 2: Map scopes to authorized claims**
```scala
val scopeClaimsMap = Map(
  "openid" -> Set("sub"),
  "profile" -> Set("name", "family_name", "given_name", "middle_name", "nickname",
                   "preferred_username", "profile", "picture", "website", "gender",
                   "birthdate", "zoneinfo", "locale", "updated_at"),
  "email" -> Set("email", "email_verified"),
  "address" -> Set("address"),
  "phone" -> Set("phone_number", "phone_number_verified")
)

val authorizedClaims = grantedScopes.flatMap(scope => scopeClaimsMap.getOrElse(scope, Set.empty))
```

**Step 3: Apply claims parameter filter (if present)**
- If authorization request included `claims.userinfo` parameter, intersect with those claims
- If no claims parameter, use all authorized claims from scopes

**Step 4: Extract locale preferences from JWT**
```scala
val uiLocales: Vector[String] = // from JWT "ui_locales" claim
// Example: Vector("fr-CA", "fr", "en")
```

**Step 5: Fetch user claims from database**
```sql
SELECT claims FROM users WHERE id = :userId
```

**Step 6: Resolve localized claims**
```scala
def resolveLocalizedClaim(
  claimName: String,
  userClaims: Map[String, Json],
  locales: Vector[String]
): Option[(String, Json)] = {
  // Try exact locale match first (e.g., "name#fr-CA")
  locales.flatMap { locale =>
    userClaims.get(s"$claimName#$locale")
      .map(value => (s"$claimName#$locale", value))
  }.headOption
    .orElse {
      // Try language-only match (e.g., "name#fr" for "fr-CA")
      locales.flatMap { locale =>
        val lang = locale.split("-").head
        userClaims.get(s"$claimName#$lang")
          .map(value => (s"$claimName#$lang", value))
      }.headOption
    }
    .orElse {
      // Fallback to default claim (e.g., "name")
      userClaims.get(claimName).map(value => (claimName, value))
    }
}
```

**Step 7: Filter and return only authorized claims with locale resolution**
```scala
val userClaims: Map[String, Json] = // from database JSONB
val response = authorizedClaims.flatMap { claimName =>
  resolveLocalizedClaim(claimName, userClaims, uiLocales)
}.toMap

// Always include 'sub' if openid scope granted
response + ("sub" -> Json.fromString(userId.toString))
```

### 2.4 Token Validation Flow

**JWT Access Token Validation:**
1. Parse JWT from Authorization header
2. Verify signature using JWK from configuration
3. Check expiration (`exp` claim)
4. Extract `sub` (user ID) and `scope` claims
5. Proceed to claims resolution

### 2.5 Claims Parameter Storage

The `claims` parameter from the authorization request must be stored in the authorization code and propagated to the token:

**Authorization Code Record:**
```scala
case class AuthorizationCodeRecord(
  // ... existing fields
  requestedClaims: Option[RequestedClaims],
  uiLocales: Option[Vector[String]], // BCP47 language tags
)

case class RequestedClaims(
  userinfo: Map[Claim, ClaimRequest],
  @jsonField("id_token") idToken: Map[String, ClaimRequest],
)

case class ClaimRequest(
  essential: Option[Boolean],
  value: Option[String],
  values: Option[Vector[String]],
)
```

**JWT Access Token Model:**
```scala
case class AccessToken(
  @jsonField("sub") userId: UserId,
  @jsonField("client_id") clientId: ClientId,
  scope: Set[ScopeToken],
  @jsonField("requested_claims") requestedClaims: Option[RequestedClaims],
  @jsonField("ui_locales") uiLocales: Option[Vector[String]],
  @jsonField("exp") expiresAt: Instant,
  @jsonField("iat") issuedAt: Instant,
  @jsonField("nbf") notBefore: Option[Instant],
  @jsonField("aud") audience: Vector[ClientId],
  @jsonField("iss") issuer: Option[String],
  @jsonField("jti") jwtId: Option[String],
)
```

**JWT Payload Example:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "test-client-123",
  "scope": "openid profile email",
  "requested_claims": {
    "userinfo": {
      "email": {"essential": true},
      "name": null
    },
    "id_token": {}
  },
  "ui_locales": ["fr-CA", "fr", "en"],
  "exp": 1678901234,
  "iat": 1678900234,
  "aud": ["test-client-123"],
  "iss": "https://auth.example.com",
  "jti": "unique-jwt-id"
}
```

## 3. Implementation Components

### 3.1 Database Schema

**Users Table** (`V1__users_table_creation.sql`):
```sql
CREATE TABLE users (
    id UUID NOT NULL PRIMARY KEY,
    email TEXT,
    phone TEXT,
    claims JSONB NOT NULL
);
```

**Authorization Codes Table** (`V11__authorization_codes_table.sql`):
```sql
CREATE TABLE authorization_codes (
    code BYTEA PRIMARY KEY,
    client_id TEXT NOT NULL,
    user_id UUID NOT NULL,
    session_id BYTEA NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    code_challenge TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_claims JSONB,
    ui_locales TEXT[]
);
```

**Refresh Tokens Table** (`V6__tokens_tables.sql`):
```sql
CREATE TABLE refresh_tokens(
    id BYTEA PRIMARY KEY,
    session_id BYTEA NOT NULL,
    user_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_claims JSONB,
    ui_locales TEXT[]
);
```

**Note:** Access tokens are JWTs and not stored in database. The `requested_claims` and `ui_locales` are embedded in the JWT itself.

### 3.2 Scala Components

**Models:**
- `AccessToken` (JWT) - Parsed and validated JWT access token with all claims
- `UserInfoResponse` - JSON response with user claims
- `UserInfoError` - Error types (InvalidToken, InsufficientScope, Unauthorized)
- `RequestedClaims` - Claims parameter from authorization request
- `ClaimRequest` - Individual claim request with essential, value, values fields

**Services:**
- `UserInfoService` - Core business logic for claims resolution and locale handling (includes locale resolution logic)
- `UserRepository` - Database operations for user claims
- `OAuthClientService` - Provides cached scope-to-claims mapping

**Controllers:**
- `UserInfoController` - HTTP endpoint handler for GET/POST `/v1/userinfo`

### 3.3 Standard Claims Mapping

According to OpenID Connect Core 1.0 Section 5.1, standard claims include:

| Claim | Type | Description |
|-------|------|-------------|
| `sub` | string | Subject identifier (user ID) - REQUIRED |
| `name` | string | Full name |
| `given_name` | string | Given name(s) or first name(s) |
| `family_name` | string | Surname(s) or last name(s) |
| `middle_name` | string | Middle name(s) |
| `nickname` | string | Casual name |
| `preferred_username` | string | Shorthand name |
| `profile` | string | Profile page URL |
| `picture` | string | Profile picture URL |
| `website` | string | Web page or blog URL |
| `email` | string | Email address |
| `email_verified` | boolean | True if email verified |
| `gender` | string | Gender |
| `birthdate` | string | Birthday (YYYY-MM-DD format) |
| `zoneinfo` | string | Time zone (e.g., "Europe/Paris") |
| `locale` | string | Locale (e.g., "en-US") |
| `phone_number` | string | Phone number (E.164 format) |
| `phone_number_verified` | boolean | True if phone verified |
| `address` | object | Postal address (structured) |
| `updated_at` | number | Time info last updated (Unix timestamp) |

**Note:** Values stored in their native JSON types in JSONB:
- Strings as JSON strings
- Booleans as JSON booleans (`true`/`false`)
- Numbers as JSON numbers
- Objects as nested JSON objects (e.g., `address`)
- Arrays as JSON arrays (for future multi-valued claims)

### 3.4 Localized Claims Support

**Locale Storage Format:**

Localized claims are stored with language tag suffixes following BCP47 format:

```json
{
  "name": "John Doe",
  "name#fr": "Jean Dupont",
  "name#fr-CA": "Jean Dupont",
  "name#es": "Juan Pérez",
  "given_name": "John",
  "given_name#fr": "Jean",
  "given_name#es": "Juan",
  "family_name": "Doe",
  "family_name#fr": "Dupont",
  "family_name#es": "Pérez"
}
```

**Authorization Request with ui_locales:**

```http
GET /v1/authorize?
  client_id=client123&
  scope=openid%20profile&
  ui_locales=fr-CA%20fr%20en&
  ...
```

**Locale Resolution Algorithm:**

1. **Extract locale preferences** from JWT `ui_locales` claim (e.g., `["fr-CA", "fr", "en"]`)
2. **For each requested claim**, try to find localized version:
   - Try exact locale match: `name#fr-CA`
   - Try language-only match: `name#fr` (extract language from `fr-CA`)
   - Fallback to default: `name`
3. **Return the first match found** in the preference order

**Example UserInfo Response with Locales:**

Request with `ui_locales=fr-CA fr en`:
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name#fr-CA": "Jean Dupont",
  "given_name#fr": "Jean",
  "family_name#fr": "Dupont",
  "email": "jean@example.com",
  "email_verified": true
}
```

**Supported Language Tags:**
- Simple language: `fr`, `en`, `es`, `de`, `ja`, `zh`
- Language with region: `fr-CA`, `en-US`, `en-GB`, `es-MX`, `zh-CN`
- Full BCP47 tags supported (e.g., `zh-Hans-CN`)

**Implementation Notes:**
- Locales are optional; if not provided, only default claims returned
- Localized claims only returned if they exist in user's claims JSONB
- Multiple localized versions can be returned if client requests them explicitly
- Language tag matching is case-insensitive
- Invalid language tags are ignored

## 4. Security Analysis

| Threat | Protection Layer / Defense Mechanism |
|--------|--------------------------------------|
| **Token theft** | HTTPS required for all requests; short-lived access tokens (configurable TTL) |
| **Token replay** | Token expiration check; optional token revocation list support |
| **Unauthorized claim access** | Scope-based authorization; claims parameter filtering |
| **Privacy leakage** | Only return explicitly requested and authorized claims |
| **Token forgery** | JWT signature verification (RSA/ECDSA/HMAC) |
| **Expired token usage** | Strict expiration validation on every request |
| **Scope escalation** | Scopes validated against client's allowed scopes during authorization |
| **Claims injection** | Claims read from trusted database; no user-supplied claims in response |

## 5. Claims Parameter Handling

### 5.1 Authorization Request with Claims Parameter

**Example Authorization Request:**
```http
GET /v1/authorize?
  client_id=client123&
  redirect_uri=https://app.example.com/callback&
  scope=openid%20profile%20email&
  response_type=code&
  code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&
  code_challenge_method=S256&
  ui_locales=fr-CA%20fr%20en&
  claims=%7B%22userinfo%22%3A%7B%22given_name%22%3A%7B%22essential%22%3Atrue%7D%2C%22email%22%3Anull%7D%7D
```

**Decoded claims parameter:**
```json
{
  "userinfo": {
    "given_name": {"essential": true},
    "email": null
  }
}
```

**Note:** The `ui_locales` parameter is stored alongside the claims parameter and used during UserInfo response generation to return localized claim values.

### 5.2 Claims Parameter Validation

**Rules:**
1. Claims parameter is optional
2. Must be valid JSON object
3. May contain `userinfo` and/or `id_token` members
4. Each claim may be `null` or an object with `essential`, `value`, `values` properties
5. Requested claims must be subset of claims authorized by granted scopes
6. If `essential: true` and claim unavailable, may affect authorization decision

### 5.3 Claims Resolution Priority

**Priority order:**
1. **Explicit claims parameter** - If present, use only these claims (intersected with scope-authorized claims)
2. **Scope-based claims** - If no claims parameter, use all claims authorized by granted scopes
3. **Available user claims** - Only return claims that exist in user's claims JSONB

**Example:**
- Granted scopes: `openid profile email`
- Claims parameter: `{"userinfo": {"given_name": null, "email": null}}`
- Authorized claims: `given_name`, `email` (intersection of scope claims and requested claims)
- User has: `{sub, name, given_name, email, phone_number}`
- Response: `{sub, given_name, email}` (only authorized and available claims)

## 6. Response Format

### 6.1 Successful Response

**Content-Type:** `application/json`
**Status:** `200 OK`

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name": "John Doe",
  "given_name": "John",
  "family_name": "Doe",
  "email": "john.doe@example.com",
  "email_verified": true,
  "phone_number": "+1234567890",
  "phone_number_verified": false,
  "picture": "https://example.com/avatar.jpg"
}
```

### 6.2 Error Responses

**Invalid Token (401 Unauthorized):**
```json
{
  "error": "invalid_token",
  "error_description": "The access token is invalid, expired, or malformed"
}
```

**Insufficient Scope (403 Forbidden):**
```json
{
  "error": "insufficient_scope",
  "error_description": "The access token does not have sufficient scope to access this resource"
}
```

**Headers:**
- `WWW-Authenticate: Bearer error="invalid_token"` (for 401 responses)
- `Cache-Control: no-store`
- `Pragma: no-cache`

## 7. Implementation Notes

### 7.1 Backward Compatibility

- `sub` claim always returned if `openid` scope granted (even if not in claims JSONB)
- Claims stored as native JSON types (strings, booleans, numbers, objects, arrays)
- Empty claims object `{}` is valid

### 7.2 Performance Optimizations

- Cache scope-to-claims mapping in memory via `OAuthClientService.getAllScopesCached`
- Single database query to fetch user claims
- Locale resolution performed in-memory after fetching claims

## 8. Future Enhancements

This section documents planned features from OpenID Connect Core 1.0 specification that are not yet implemented.

### 8.1 Aggregated and Distributed Claims

**Status:** Planned
**Specification:** OpenID Connect Core 1.0 Section 5.6.2

**Aggregated Claims:**
Claims sourced from external providers, aggregated by the authorization server and returned as a JWT.

**Example Response:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name": "John Doe",
  "email": "john@example.com",
  "_claim_names": {
    "credit_score": "src1",
    "payment_info": "src1"
  },
  "_claim_sources": {
    "src1": {
      "JWT": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
    }
  }
}
```

**Implementation Requirements:**
- Add `claim_sources` table to store external claim provider configurations
- Add `user_aggregated_claims` table to cache aggregated claims with TTL
- Implement JWT verification for aggregated claim JWTs
- Support configurable claim source endpoints
- Cache aggregated claims to reduce external API calls

**Database Schema:**
```sql
CREATE TABLE claim_sources (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  endpoint_url TEXT NOT NULL,
  jwks_uri TEXT NOT NULL,
  client_id VARCHAR(255),
  client_secret BYTEA,
  ttl_seconds INTEGER NOT NULL DEFAULT 3600,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_aggregated_claims (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_id UUID NOT NULL REFERENCES claim_sources(id) ON DELETE CASCADE,
  claims_jwt TEXT NOT NULL,
  fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, source_id)
);

CREATE INDEX user_aggregated_claims_expires_at_idx
  ON user_aggregated_claims(expires_at);
```

**Distributed Claims:**
Claims that the client must fetch directly from external sources.

**Example Response:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "name": "John Doe",
  "_claim_names": {
    "credit_score": "src1",
    "payment_info": "src1"
  },
  "_claim_sources": {
    "src1": {
      "endpoint": "https://claims.example.com/userinfo",
      "access_token": "ksj3n283dke"
    }
  }
}
```

**Implementation Requirements:**
- Generate short-lived access tokens for external claim sources
- Store claim source metadata in configuration
- Return endpoint URLs and access tokens in `_claim_sources`
- Implement access token validation for external claim endpoints

### 8.2 Signed and Encrypted UserInfo Responses

**Status:** Planned
**Specification:** OpenID Connect Core 1.0 Section 5.3.2

**Signed UserInfo Response (JWT):**
Return UserInfo as a signed JWT instead of plain JSON.

**Request:**
```http
GET /v1/userinfo HTTP/1.1
Host: auth.example.com
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Accept: application/jwt
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Type: application/jwt

eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDAiLCJuYW1lIjoiSm9obiBEb2UiLCJlbWFpbCI6ImpvaG5AZXhhbXBsZS5jb20ifQ.signature
```

**Implementation Requirements:**
- Check `Accept: application/jwt` header
- Sign UserInfo response using server's private key
- Include standard JWT claims: `iss`, `sub`, `aud`, `iat`, `exp`
- Support client-specific signing algorithm preferences (from client registration)

**Encrypted UserInfo Response (JWE):**
Return UserInfo as an encrypted JWT for enhanced privacy.

**Implementation Requirements:**
- Client must register public key during client registration
- Encrypt UserInfo JWT using client's public key (JWE)
- Support multiple encryption algorithms: RSA-OAEP, ECDH-ES
- Nested JWT: Sign first (JWS), then encrypt (JWE)

**Client Registration Extensions:**
```json
{
  "client_id": "client123",
  "userinfo_signed_response_alg": "RS256",
  "userinfo_encrypted_response_alg": "RSA-OAEP",
  "userinfo_encrypted_response_enc": "A256GCM",
  "jwks_uri": "https://client.example.com/jwks"
}
```

**Database Schema:**
```sql
ALTER TABLE oauth_clients ADD COLUMN userinfo_signed_response_alg VARCHAR(50);
ALTER TABLE oauth_clients ADD COLUMN userinfo_encrypted_response_alg VARCHAR(50);
ALTER TABLE oauth_clients ADD COLUMN userinfo_encrypted_response_enc VARCHAR(50);
ALTER TABLE oauth_clients ADD COLUMN jwks_uri TEXT;
ALTER TABLE oauth_clients ADD COLUMN jwks JSONB;
```

### 8.3 Essential Claims Enforcement

**Status:** Planned
**Specification:** OpenID Connect Core 1.0 Section 5.5.1

**Essential Claims:**
Enforce that essential claims must be available, or deny authorization.

**Example Claims Parameter:**
```json
{
  "userinfo": {
    "email": {"essential": true},
    "email_verified": {"essential": true},
    "phone_number": null
  }
}
```

**Implementation Requirements:**
- Parse `essential` flag from claims parameter
- Check if essential claims are available in user's claims JSONB
- If essential claim missing, return error during authorization
- Store essential claims list in authorization code
- Validate essential claims before issuing access token

**Error Response:**
```json
{
  "error": "access_denied",
  "error_description": "Essential claims not available: email_verified"
}
```

### 8.5 Claim Value Filtering

**Status:** Planned
**Specification:** OpenID Connect Core 1.0 Section 5.5.1

**Value and Values Parameters:**
Request specific claim values.

**Example:**
```json
{
  "userinfo": {
    "acr": {"values": ["urn:mace:incommon:iap:silver", "urn:mace:incommon:iap:bronze"]},
    "email": {"value": "john@example.com"}
  }
}
```

**Implementation Requirements:**
- Parse `value` and `values` from claim requests
- Filter claims to match requested values
- Return claim only if value matches
- Support for verification purposes

## 9. References

1. **OpenID Connect Core 1.0** - Section 5.3 UserInfo Endpoint
   https://openid.net/specs/openid-connect-core-1_0.html#UserInfo

2. **OpenID Connect Core 1.0** - Section 5.1 Standard Claims
   https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims

3. **OpenID Connect Core 1.0** - Section 5.5 Requesting Claims using the "claims" Request Parameter
   https://openid.net/specs/openid-connect-core-1_0.html#ClaimsParameter

4. **RFC 6750** - The OAuth 2.0 Authorization Framework: Bearer Token Usage
   https://datatracker.ietf.org/doc/html/rfc6750

5. **RFC 7519** - JSON Web Token (JWT)
   https://datatracker.ietf.org/doc/html/rfc7519

6. **PostgreSQL JSONB Documentation**
   https://www.postgresql.org/docs/current/datatype-json.html

7. **BCP47** - Tags for Identifying Languages
   https://www.rfc-editor.org/rfc/bcp/bcp47.txt

8. **OpenID Connect Core 1.0** - Section 5.2 Claims Languages and Scripts
   https://openid.net/specs/openid-connect-core-1_0.html#ClaimsLanguagesAndScripts

## 10. Conclusion

The UserInfo endpoint implementation follows OpenID Connect Core 1.0 specification while leveraging PostgreSQL JSONB for flexible claims storage. The design supports both scope-based and claims parameter-based authorization, ensuring privacy and security while maintaining high performance for resource servers.

**Implemented Features:**
- ✅ JSONB storage for flexible, schema-less claims with native JSON types
- ✅ Scope-to-claims mapping from oauth_scopes table (cached in memory)
- ✅ Claims parameter support for fine-grained control (`RequestedClaims`)
- ✅ **Localized claims support with BCP47 language tags** (e.g., `name#fr-CA`)
- ✅ **ui_locales parameter** stored in authorization codes, refresh tokens, and JWT access tokens
- ✅ Locale resolution algorithm with fallback (exact match → language match → default)
- ✅ JWT access tokens only (stateless, no database storage)
- ✅ Single database query for user claims (optimal performance)
- ✅ GET and POST methods for `/v1/userinfo` endpoint
- ✅ RFC 6750 compliant error responses with `WWW-Authenticate` header

**Future Enhancements:**
All planned enhancements are based on OpenID Connect Core 1.0 specification (see Section 8).

