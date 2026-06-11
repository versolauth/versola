# ADR 04: Token Storage and Delivery in Edge Service

**Status:** Proposed
**Date:** 2026-05-06
**Author:** Georgii Kovalev
**Context:** Token-Mediating Backend pattern for edge service OAuth flow

## Executive Summary

This ADR addresses how OAuth access tokens should be stored and delivered to browser clients in the edge service's Token-Mediating Backend (TMB) pattern. The edge service exchanges authorization codes for tokens and must decide how to securely provide these tokens to the browser for subsequent API calls.

**Core Design Decision:**
```
Approach: Encrypted access tokens in HttpOnly cookies
Storage:  Tokens encrypted server-side (DB), delivered via HttpOnly cookie
Access:   Browser automatically includes cookie; no JavaScript access
CSRF:     Protected by SameSite=Strict + state parameter validation
```

**Request Flow:**
```
Login  → Edge stores encrypted token in DB, issues session cookie
API Call → Browser auto-sends cookie → Edge decrypts token from DB → Forwards with Authorization header
```

## 1. Context and Requirements

### 1.1 Current Edge Service Architecture

The edge service implements a **Token-Mediating Backend (TMB)** pattern:

1. **Login Flow:**
   - Browser → `POST /v1/login` → Edge returns authorization URL
   - Browser → Auth Server → User authenticates
   - Auth Server → `GET /complete?code=...` → Edge service
   - Edge exchanges code for tokens, encrypts and stores in DB

2. **Current Token Storage (As-Is):**
   ```scala
   // Tokens are AES-256-GCM encrypted before storage
   accessTokenEncrypted <- securityService.encryptAes256(
     tokenResponse.accessToken.getBytes("UTF-8"),
     encryptionKey,
   )

   // Stored in edge_refresh_tokens table
   INSERT INTO edge_refresh_tokens (
     id, preset_id, refresh_token, expires_at, ...
   )
   ```

3. **Current Session Delivery:**
   - Edge returns `sessionId` as JSON response
   - Browser must explicitly call `GET /v1/sessions/:id` to retrieve session data
   - Session data includes encrypted tokens (currently exposed to browser)

### 1.2 Security Requirements

**Threats to Mitigate:**
- **XSS (Cross-Site Scripting):** Malicious JS stealing tokens from browser storage
- **CSRF (Cross-Site Request Forgery):** Attacker tricking browser into making authenticated requests
- **Token Leakage:** Tokens visible in DevTools, logs, or third-party scripts
- **Session Hijacking:** Stolen session IDs used by attackers

**Compliance:**
- IETF OAuth 2.0 for Browser-Based Apps (draft-ietf-oauth-browser-based-apps)
- OWASP Token Storage Best Practices
- Token confidentiality (especially refresh tokens with 24-hour lifetime)

### 1.3 Use Case

**Primary Flow:**
1. User authenticates via OAuth (2 requests to edge: `/v1/login` + `/complete`)
2. Browser needs access token to call resource APIs (e.g., `/api/users`, `/api/orders`)
3. Browser makes 100+ API calls during 24-hour session
4. Access tokens expire after 1 hour; refresh token valid for 24 hours

**Key Question:**
> "Should we: (A) store tokens in DB and fetch on every request, (B) put tokens in HttpOnly cookies, or (C) send tokens to browser (localStorage/memory)?"

## 2. Options Analysis

### Option 1: Database Lookup on Every Request (Current Implementation)

**Architecture:**
```
Browser                    Edge Service               Database
   │                            │                         │
   │ GET /api/users             │                         │
   │ Cookie: session_id=abc     │                         │
   ├───────────────────────────>│                         │
   │                            │  SELECT access_token    │
   │                            │  FROM edge_refresh_tokens │
   │                            │  WHERE id = 'abc'       │
   │                            ├────────────────────────>│
   │                            │<────────────────────────┤
   │                            │  Decrypt token          │
   │                            │                         │
   │                            │  Forward to Resource API│
   │                            │  Authorization: Bearer {token}
```

**Implementation:**
```scala
// Browser sends session cookie
val sessionId = request.cookie("EDGE_SESSION")

// Edge looks up session in DB
session <- repository.find(sessionId)

// Decrypt access token
accessToken <- securityService.decryptAes256(
  Base64.decode(session.accessTokenEncrypted),
  encryptionKey
)

// Forward to resource API
httpClient.get(resourceUrl)
  .addHeader("Authorization", s"Bearer ${new String(accessToken)}")
```

**Advantages:**
- ✅ **Maximum security:** Tokens never leave server, never in browser
- ✅ **XSS immune:** No JavaScript can access tokens
- ✅ **Centralized revocation:** Delete session = instant token invalidation
- ✅ **Audit trail:** Every token use logged via session lookup
- ✅ **Token rotation:** Easy to update tokens without browser involvement

**Disadvantages:**
- ❌ **Database query per API call:** Extra latency (~5-20ms per request)
- ❌ **Database load:** 100 API calls = 100 DB queries (session + decrypt)
- ❌ **Decryption overhead:** AES-256-GCM decryption on every request
- ❌ **Scaling bottleneck:** Database becomes critical path for all API calls
- ❌ **Not true TMB:** Token-Mediating Backend should give tokens to frontend

**Performance Impact:**
```
Request latency breakdown:
  Cookie extraction:      <1ms
  DB query:              5-20ms  ← Added latency
  Decrypt token:         1-3ms   ← Added latency
  Forward to API:        50-100ms
  Total:                 56-123ms vs 51-101ms (direct)
```

### Option 2: Encrypted Access Token in HttpOnly Cookie (Recommended)

**Architecture:**
```
Browser                    Edge Service               Resource API
   │                            │                         │
   │ GET /api/users             │                         │
   │ Cookie: ACCESS_TOKEN=enc(t)│                         │
   │ Cookie: SESSION_ID=abc     │                         │
   ├───────────────────────────>│                         │
   │                            │  Decrypt cookie value   │
   │                            │  (no DB lookup needed)  │
   │                            │                         │
   │                            │  Forward to Resource API│
   │                            ├────────────────────────>│
   │                            │<────────────────────────┤
   │<───────────────────────────┤                         │
```

**Implementation:**
```scala
// On login completion - issue encrypted token cookie
val encryptedToken = securityService.encryptAes256(accessToken, key)
val accessTokenCookie = Cookie.Response(
  name = "EDGE_ACCESS_TOKEN",
  content = Base64Url.encode(encryptedToken),
  isHttpOnly = true,        // ← Not accessible via JavaScript
  isSecure = true,          // ← HTTPS only
  sameSite = SameSite.Strict, // ← CSRF protection
  maxAge = Some(tokenTtl),
  path = Some(Path.root),
)

// On API request - decrypt cookie value (no DB!)
val encryptedToken = request.cookie("EDGE_ACCESS_TOKEN")
val accessToken = securityService.decryptAes256(
  Base64Url.decode(encryptedToken.content),
  encryptionKey
)
```

**Advantages:**
- ✅ **No DB lookup per request:** Token in cookie, decrypt locally
- ✅ **XSS protected:** HttpOnly prevents JavaScript access
- ✅ **CSRF protected:** SameSite=Strict blocks cross-site requests
- ✅ **Auto-included:** Browser sends cookie automatically
- ✅ **Stateless verification:** Edge can validate without DB
- ✅ **Lower latency:** No DB round-trip for token retrieval
- ✅ **Scalable:** Edge service can be horizontally scaled without session affinity

**Disadvantages:**
- ⚠️ **Cookie size limits:** ~4KB per cookie (JWT can be 1-2KB encrypted)
- ⚠️ **Revocation complexity:** Cookie valid until expiry (mitigated by short TTL)
- ⚠️ **Encryption key management:** Key rotation requires careful handling
- ⚠️ **Token in browser storage:** Persisted on disk (encrypted) - mitigated since user can't decrypt

**Security Properties:**
```
Threat               | Protection
─────────────────────┼──────────────────────────────────
XSS (steal token)    | HttpOnly flag prevents JS access
CSRF                 | SameSite=Strict blocks cross-origin
Network sniffing     | Secure flag forces HTTPS
Disk forensics       | Token is AES-256-GCM encrypted
Server compromise    | Same as Option 1 (key on server)
```

**Performance Impact:**
```
Request latency breakdown:
  Cookie extraction:      <1ms
  Decrypt token:         1-3ms   ← Only added latency
  Forward to API:        50-100ms
  Total:                 51-104ms (vs 56-123ms for Option 1)

Savings: ~5-20ms per API call (DB query eliminated)
```

### Option 3: Plain Token in Browser (localStorage / JS-Accessible Cookie)

**Architecture:**
```
Browser                    Edge Service               Resource API
   │                            │                         │
   │ POST /v1/login             │                         │
   ├───────────────────────────>│                         │
   │ { accessToken: "eyJ..." }  │                         │
   │<───────────────────────────┤                         │
   │ // store in localStorage   │                         │
   │                            │                         │
   │ GET /api/users             │                         │
   │ Authorization: Bearer ...  │                         │
   ├──────────────────────────────────────────────────────>│
   │<──────────────────────────────────────────────────────┤
```

**Implementation:**
```javascript
// Browser stores raw token
localStorage.setItem("accessToken", response.accessToken)

// Browser calls API directly (no edge involved)
fetch("/api/users", {
  headers: { "Authorization": `Bearer ${localStorage.getItem("accessToken")}` }
})
```

**Advantages:**
- ✅ **Lowest edge load:** Edge service only used for login
- ✅ **Direct API calls:** No proxy hop
- ✅ **Simple client logic:** Standard Bearer token usage

**Disadvantages:**
- ❌ **XSS vulnerable:** Any injected script can steal tokens
- ❌ **No HttpOnly protection:** localStorage/sessionStorage always JS-accessible
- ❌ **Refresh token exposure:** If refresh tokens stored, attacker has long-lived access
- ❌ **Violates BCP for browser apps:** IETF explicitly recommends against this
- ❌ **No revocation control:** Edge cannot invalidate tokens in browser storage

**Why Rejected:**
The IETF OAuth 2.0 for Browser-Based Apps draft explicitly recommends against
storing tokens in browser-accessible storage when a backend is available. Since
we already have an edge service performing the OAuth exchange, exposing raw
tokens to JavaScript would defeat the purpose of running a backend mediator.

## 3. Decision

**Adopt Option 2: Encrypted Access Token in HttpOnly Cookie.**

The edge service will, upon successful OAuth exchange, set the following cookies
on the response:

| Cookie | Contents | Flags | Lifetime |
|--------|----------|-------|----------|
| `EDGE_SESSION` | Opaque session id | `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/` | Session lifetime (24h) |
| `EDGE_ACCESS_TOKEN` | AES-256-GCM(access_token), Base64Url | `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/` | Access token TTL (~1h) |

**The refresh token stays in the database only.** It is never delivered to the
browser in any form. When `EDGE_ACCESS_TOKEN` expires, the browser calls a
dedicated endpoint (`POST /v1/sessions/refresh`) which uses `EDGE_SESSION` to
look up the refresh token, mints a new access token, and re-issues the
`EDGE_ACCESS_TOKEN` cookie.

**Database stays as the source of truth for revocation.** The encrypted access
token in the cookie is a performance cache; the refresh token row in `edge_refresh_tokens`
remains authoritative. Logout deletes the token row and clears both cookies.

### 3.1 Request Lifecycle

```
Login   → /complete sets EDGE_SESSION + EDGE_ACCESS_TOKEN cookies
API call → Edge decrypts EDGE_ACCESS_TOKEN cookie, forwards Bearer to upstream
Refresh → /v1/sessions/refresh uses EDGE_SESSION → DB → new EDGE_ACCESS_TOKEN
Logout  → /v1/sessions/logout deletes session row, clears both cookies
```

### 3.2 Encryption

- Algorithm: AES-256-GCM (already used by `SecurityService.encryptAes256`)
- Key source: same `encryptionKey` used for at-rest token encryption
- Encoding: Base64Url for cookie-safe transport
- Format: `nonce || ciphertext || tag` (as produced by existing helper)

### 3.3 Why Not Option 1

Option 1 forces a database round-trip on every API call. With expected traffic
of 100+ API calls per session, this multiplies database load and adds
5–20 ms of latency per request without improving the security posture: in both
options the encryption key lives on the edge service, so a server compromise
yields the same outcome.

### 3.4 Why Not Option 3

Option 3 puts raw bearer tokens in JavaScript-reachable storage, which is
explicitly discouraged by the IETF Browser-Based Apps BCP and removes the
ability of the edge service to enforce revocation or rotation.

## 4. Consequences

### 4.1 Positive

- **Lower latency** for the hot path (API calls): no DB query, only an
  in-memory AES-GCM decryption (~1–3 ms).
- **Horizontal scalability** of the edge service: any instance can serve any
  request because token state travels in the cookie.
- **XSS protection** preserved via `HttpOnly`; **CSRF protection** via
  `SameSite=Strict`.
- **Refresh token never leaves the server**, keeping the high-value, long-lived
  credential off the client entirely.

### 4.2 Negative

- **Revocation lag:** an `EDGE_ACCESS_TOKEN` cookie remains usable until its
  TTL expires even after the session row is deleted. Mitigated by short access
  token TTL (≤ 1 hour) and by the upstream resource server validating the
  token.
- **Encryption key rotation** is more involved: rotated keys must be retained
  for the lifetime of the longest outstanding cookie so old cookies still
  decrypt. Acceptable given the short access token TTL.
- **Cookie size budget:** roughly 1.5–2× the raw token size after AES-GCM and
  Base64Url. Must stay below the 4 KB per-cookie limit; current OAuth access
  tokens (opaque, ~64 bytes) are well within budget.

### 4.3 Follow-Up Work

- Add `EDGE_ACCESS_TOKEN` issuance in `CompleteController` after the OAuth
  exchange completes.
- Add `POST /v1/sessions/refresh` that uses `EDGE_SESSION` to mint a fresh
  access token cookie from the stored refresh token.
- Add `POST /v1/sessions/logout` that deletes the session row and clears both
  cookies via `Max-Age=0`.
- Stop returning raw or encrypted tokens in the JSON body of
  `GET /v1/sessions/:id` — that endpoint should expose only non-sensitive
  session metadata (user id, expiry).
- Document the encryption key rotation procedure once a second active key slot
  is introduced.


