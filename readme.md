# Versola

Scala Toolkit for building your own OAuth 2.1 and OpenID Connect authorization server.

**We do what matters, you do what business needs.**

Under active development.

## Features

### OAuth 2.1 & OpenID Connect Implementation Status

#### RFC 6749 - OAuth 2.0 Core

**Authorization Endpoint** (`/v1/authorize`)
- [x] Authorization Code Flow
- [x] PKCE (code_challenge, code_challenge_method: S256, plain)
- [x] `scope` parameter
- [x] `state` parameter
- [x] `redirect_uri` validation
- [x] `claims` parameter (OpenID Connect)
- [x] `ui_locales` parameter (OpenID Connect)
- [x] GET and POST methods
- [x] Error handling with redirect
- [ ] `response_type=code id_token` (hybrid flow)
- [ ] `prompt` parameter (none, login, consent, select_account)
- [ ] `max_age` parameter
- [ ] `id_token_hint` parameter
- [ ] `acr_values` parameter

**Token Endpoint** (`/v1/token`)
- [x] **Authorization Code Grant** (`grant_type=authorization_code`)
  - [x] PKCE verification (mandatory)
  - [x] Single-use code enforcement
  - [x] Client authentication (client_secret_basic, client_secret_post)
  - [x] `redirect_uri` validation
- [x] **Refresh Token Grant** (`grant_type=refresh_token`)
  - [x] Refresh token rotation
  - [x] `previous_id` tracking
  - [x] Concurrent rotation detection (REPEATABLE READ isolation)
  - [x] Scope downgrading
  - [x] `offline_access` scope requirement
- [x] **Client Credentials Grant** (`grant_type=client_credentials`)
  - [x] Confidential clients only
  - [x] No user context (subject = client_id)
  - [x] Scope validation
- [x] JWT access tokens (stateless, RS256)
- [x] Opaque refresh tokens (database-stored, BLAKE3-MAC)
- [x] Error responses (RFC 6749 Section 5.2)
- [ ] Token revocation on authorization code reuse
- [ ] Device Code Grant (RFC 8628)
- [ ] JWT Bearer Grant (RFC 7523)

#### RFC 7009 - Token Revocation
- [x] `/v1/revoke` endpoint
- [x] Refresh token revocation

#### RFC 7662 - Token Introspection
- [x] `/v1/introspect` endpoint
- [x] Access token introspection (JWT validation)
- [x] Refresh token introspection (database lookup)
- [x] Client authentication required
- [x] `active` boolean response
- [x] Token metadata (scope, client_id, exp, iat, sub)
- [x] Inactive response for invalid/expired tokens
- [x] Audience validation (`aud` claim check)

#### RFC 7636 - PKCE (Proof Key for Code Exchange)
- [x] `code_challenge` parameter (43-128 characters, base64url)
- [x] `code_challenge_method` (S256, plain)
- [x] `code_verifier` validation (43-128 characters)
- [x] **Mandatory for all clients** (OAuth 2.1 requirement)
- [x] Authorization code storage with challenge
- [x] Token endpoint verification

#### OpenID Connect Core 1.0

**UserInfo Endpoint** (`/v1/userinfo`)
- [x] Core functionality (GET and POST methods)
- [x] Bearer token authentication (RFC 6750)
- [x] Scope-based claims filtering
- [x] `claims` parameter support
- [x] Localized claims (BCP47 language tags, e.g., `name#fr-CA`)
- [x] `ui_locales` parameter support with fallback
- [x] Signed JWT response (Accept: application/jwt)
- [x] Single database query optimization
- [x] JSONB claims storage
- [ ] Essential claims enforcement
- [ ] Encrypted UserInfo response (JWE)
- [ ] Aggregated claims (external claim sources)
- [ ] Distributed claims (client-fetched claims)

**ID Token**
- [ ] ID Token generation
- [ ] Required claims (`iss`, `sub`, `aud`, `exp`, `iat`)
- [ ] `nonce` parameter and claim
- [ ] `auth_time` claim
- [ ] `acr` (Authentication Context Class Reference)
- [ ] `amr` (Authentication Methods References)
- [ ] `azp` (Authorized Party) for multiple audiences
- [ ] ID Token signing (RS256)
- [ ] ID Token encryption (optional)
- [ ] `at_hash` and `c_hash` claims
- [ ] Max age validation

**Discovery Endpoint** (`/.well-known/openid-configuration`)
- [ ] Issuer URL
- [ ] Authorization endpoint
- [ ] Token endpoint
- [ ] UserInfo endpoint
- [ ] JWKS URI
- [ ] Supported scopes
- [ ] Supported response types
- [ ] Supported grant types
- [ ] Supported token endpoint auth methods
- [ ] Supported claims
- [ ] Supported code challenge methods

**JWKS Endpoint** (`/v1/jwks` or `/.well-known/jwks.json`)
- [ ] Public key exposure (RSA)
- [ ] Key rotation support
- [ ] `kid` (Key ID) in JWT header
- [ ] Multiple keys support
- [ ] Key expiration and caching headers

#### OAuth 2.1 Compliance
- [x] PKCE mandatory for all clients
- [x] Refresh token rotation with reuse detection
- [x] No implicit grant (removed in OAuth 2.1)
- [x] No resource owner password credentials grant (removed in OAuth 2.1)
- [x] Bearer token usage (RFC 6750)
- [x] Short-lived authorization codes (10 minutes)
- [ ] Exact redirect URI matching (no wildcards)
- [ ] Authorization code reuse detection with token revocation
- [ ] Refresh token sender-constrained (DPoP or mTLS)

#### Client Management
- [x] Client secret storage (BLAKE3-MAC with salt)
- [x] Secret rotation with previous secret support
- [x] Confidential vs public client types
- [x] Per-client access token TTL
- [x] Per-client allowed scopes
- [x] Per-client redirect URI whitelist
- [x] External audience configuration
- [x] Admin API for client CRUD operations
- [ ] Dynamic client registration (RFC 7591)
- [ ] Client metadata management
- [ ] Client update endpoint
- [ ] Client deletion endpoint

#### Session Management
- [x] SSO sessions table
- [x] Session-to-tokens relationship (one-to-many)
- [x] Session expiration (30 days default, configurable)
- [x] Session tracking by user_id
- [x] Session tracking by client_id
- [x] SSO conversation tracking (15-minute cookie)
- [ ] Session revocation endpoint
- [ ] Session listing endpoint
- [ ] Front-channel logout
- [ ] Back-channel logout (RFC 8984)

#### Scope Management
- [x] Scope-to-claims mapping
- [x] Database-driven scopes (PostgreSQL)
- [x] Standard OIDC scopes (openid, profile, email, address, phone, offline_access)
- [x] Custom scopes (read, write, admin)
- [x] Scope validation at authorization
- [x] Scope validation at token endpoint
- [x] Scope downgrading on refresh
- [x] In-memory scope cache
- [x] Admin API for scope CRUD operations
- [ ] Dynamic scope registration
- [ ] Scope consent UI

#### Security Features
- [x] BLAKE3-MAC for tokens and secrets
- [x] Salted password hashing (Bouncy Castle)
- [x] AES-256 encryption for external OAuth secrets
- [x] Secure random generation (UUIDv7 for IDs)
- [x] JWT signing (RS256)
- [x] Authorization code single-use enforcement
- [x] Refresh token rotation
- [x] Concurrent refresh token reuse detection
- [x] PKCE mandatory
- [x] Client secret rotation
- [x] Pepper-based MAC for all sensitive tokens
- [ ] Rate limiting
- [ ] DPoP (Demonstrating Proof-of-Possession)
- [ ] mTLS client authentication
- [ ] JWT encryption (JWE)

#### Database & Storage
- [x] PostgreSQL with JSONB for claims
- [x] Flyway migrations
- [x] HikariCP connection pooling
- [x] Magnum SQL library (compile-time safe queries)
- [x] Cleanup manager for expired records
  - [x] Authorization codes (10 min TTL)
  - [x] Auth conversations (15 min TTL)
  - [x] Refresh tokens (90 days TTL)
  - [x] SSO sessions (30 days TTL)
  - [x] Edge sessions (24 hours TTL)
- [x] SELECT FOR UPDATE SKIP LOCKED for concurrent cleanup
- [x] Configurable batch sizes and intervals
- [ ] Database sharding (premium)

#### External OAuth Providers
- [x] Google OAuth integration
- [x] GitHub OAuth integration
- [x] Provider-specific endpoints
- [x] Token exchange
- [x] UserInfo fetching
- [x] Encrypted client secret storage
- [ ] Provider discovery
- [ ] Provider token refresh
- [ ] Account linking

#### Additional Features
- [x] Admin UI (Laminar/Scala.js)
  - [x] Client management
  - [x] Scope management
  - [x] Secret rotation
- [x] Conversation-based authentication flow
  - [x] Email/phone credential collection
  - [x] OTP verification
  - [x] Password authentication
  - [x] Passkey support (WebAuthn)
- [x] Localization support (ui_locales)
- [x] Datastar SSE for dynamic UI updates
- [x] OpenTelemetry tracing
- [x] Prometheus metrics endpoint
- [x] Health check endpoints (liveness, readiness)
- [x] Graceful shutdown
- [ ] Audit logging
- [ ] Consent management UI
- [ ] Account management UI
