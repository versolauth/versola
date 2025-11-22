# ADR 00: Client Secret Storage for OAuth 2.1

**Status:** Proposed
**Date:** 2025-11-22
**Author:** Georgii Kovalev
**Context:** OAuth 2.1 Token Endpoint Authentication

## Executive Summary

This ADR documents the design decision to use **BLAKE3 MAC (Message Authentication Code) with salt+pepper key derivation** for securing OAuth 2.1 client secrets.
This approach balances high security requirements with the performance demands of high-throughput endpoints while maintaining simplicity.

**Core Design:**
```
Input: client_secret (plaintext)
Salt: 16 bytes (per-client, stored in database)
Pepper: 16 bytes (global secret, stored in app environment)
MAC Key: salt || pepper = 32 bytes
MAC: BLAKE3-keyed-hash(client_secret, MAC Key) → 32 bytes
Storage: MAC stored directly in database
```

## 1. Context and Requirements

### 1.1 OAuth 2.1 and OpenID Connect Client Authentication

In OAuth 2.1 and OpenID Connect, client secrets are used to authenticate confidential clients at multiple endpoints.
According to RFC 6749, OAuth 2.1 draft specifications:

- **Confidential clients** (server-side applications) must authenticate using their client ID and secret
- **Token endpoint** (`/token`) is the primary authentication point where client secrets are verified
  - Authorization code exchange
  - Refresh token rotation
  - Client credentials grant
- **Token introspection endpoint** (`/introspect`, RFC 7662) requires client authentication
  - Allows clients to query the authorization server about token metadata
  - Used to validate access tokens and check their status

### 1.2 Performance Requirements

OAuth 2.1 endpoints that require client authentication are typically high-traffic:

**Primary endpoints requiring client secret verification:**
- **Token endpoint** (`/token`) - High traffic, every authorization code exchange and token refresh
- **Introspection endpoint** (`/introspect`) - Highest possible traffic, resource servers check token on each request

**Performance characteristics:**
- **Low latency requirement:** All endpoints should complete in <50ms to avoid degrading user experience
- **CPU efficiency:** Cryptographic operations must not become a bottleneck under load

### 1.3 Security Requirements

Client secrets require strong protection due to their critical role:

- **Database breach protection:** If the database is compromised, secrets must remain computationally infeasible to recover
- **Defense in depth:** Separation of database and configuration secrets to require multiple breaches
- **Timing attack resistance:** Verification must be constant-time to prevent timing-based secret extraction
- **Rainbow table resistance:** Pre-computed attack tables must be infeasible
- **Configuration exposure protection:** Configuration files must be stored securely and separately from database

### 1.4 Design Constraints

- Must support secret rotation (storing both current and previous secret)
- Must integrate with existing PostgreSQL database schema
- Must be implementable with JVM-available cryptographic libraries (Bouncy Castle, Apache Commons Codec)
- Should minimize memory footprint for cached client data

## 2. Design Decision

**Approach:**
```
MAC Key = salt (16 bytes) || pepper (16 bytes) = 32 bytes
MAC = BLAKE3-keyed-hash(client_secret, MAC Key)
```

**Why not password hashing (Argon2/bcrypt)?**
- Password hashing is designed for low-entropy user passwords (~40-60 bits)
- Intentionally slow (tens of ms) to resist brute force[^1]
- Client secrets are 256-bit random - brute force infeasible regardless of speed
- BLAKE3 is much faster with equivalent security for high-entropy secrets

**Why not HMAC-SHA256?**
- BLAKE3 is 10-20x faster [^2]
- Modern design (2020) with simpler keyed hash construction
- HMAC-SHA256 more standardized (FIPS 198-1), but BLAKE3 well-established

**Advantages:**
1. **Performance:** Microseconds per operation (critical for high-load endpoints)
2. **Security:** 256-bit security level, constant-time operation (in our case)
3. **Defense in depth:** Salt (per-client, DB) + Pepper (global, app environment) requires two breaches
4. **Simplicity:** Single cryptographic primitive

[^1]: OWASP Password Storage Cheat Sheet, https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
[^2]: BLAKE3 official benchmarks https://github.com/BLAKE3-team/BLAKE3


## 3. Storage and Configuration

### 3.1 Database Schema

```sql
CREATE TABLE oauth_clients (
    id TEXT PRIMARY KEY,
    secret BYTEA,             -- BLAKE3 MAC (32 bytes) || Per-client salt (16 bytes) = 48 bytes
    previous_secret BYTEA     -- previous secret - used for rotation, same 48 bytes
);
```

**Storage:** 48 bytes per client (96 bytes with rotation support)

### 3.2 Configuration

```hocon
core.security.client-secrets {
  pepper = "..." # 16 bytes
}
```

### 3.3 Client Secret Rotation

Client secret rotation allows updating compromised or leaked secrets without service interruption.

**Rotation Process:**
1. Admin generates new secret via API/admin interface
2. New secret is hashed with new salt and stored in `secret` field
3. Old secret is moved to `previous_secret` field
4. Both secrets are valid during transition period
5. Client updates to new secret
6. Admin deletes old secret after confirming client migration

**Database state during rotation:**
```sql
-- Before rotation: only current secret
secret: MAC(current_secret, salt1 || pepper) || salt1
previous_secret: NULL

-- During rotation: both secrets valid
secret: MAC(new_secret, salt2 || pepper) || salt2
previous_secret: MAC(old_secret, salt1 || pepper) || salt1

-- After rotation: old secret deleted
secret: MAC(new_secret, salt2 || pepper) || salt2
previous_secret: NULL
```

## 4. Security Analysis

| Threat                         | Defense                                                             |
|--------------------------------|---------------------------------------------------------------------|
| **Brute force attacks**        | 256-bit random secrets (2^256 attempts infeasible)                  |
| **Rainbow tables**             | Per-client salt (16 bytes) + global pepper (16 bytes)               |
| **Database breach**            | Pepper stored separately in app environment (requires two breaches) |
| **MAC forgery**                | BLAKE3 cryptographic MAC (cannot forge or reverse without key)      |
| **Compromised client secrets** | Dual-secret rotation support (zero-downtime invalidation)           |
| **Timing attacks**             | Constant-time BLAKE3 and comparison operations                      |


## 5. Conclusion

BLAKE3 MAC with salt+pepper provides optimal security for high-entropy OAuth client secrets while maintaining exceptional performance for high-load endpoints.
The defense-in-depth approach requires two separate breaches (database + config) while keeping cryptographic operations from becoming a bottleneck.


## References

1. **OWASP Password Storage Cheat Sheet** - Argon2id recommended parameters (m=19456, t=2, p=1)
   https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html

2. **BLAKE3 Official Repository** - Performance benchmarks and specifications
   https://github.com/BLAKE3-team/BLAKE3
   "Much faster than MD5, SHA-1, SHA-2, SHA-3, and BLAKE2"

3. **BLAKE3 Specification Paper**
   https://github.com/BLAKE3-team/BLAKE3-specs/blob/master/blake3.pdf

4. **RFC 6749** - The OAuth 2.0 Authorization Framework
   https://datatracker.ietf.org/doc/html/rfc6749

5. **OAuth 2.1 Draft**
   https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11

6. **RFC 7662** - OAuth 2.0 Token Introspection
   https://datatracker.ietf.org/doc/html/rfc7662

7. **Argon2 Specification** - Password Hashing Competition Winner
   https://github.com/P-H-C/phc-winner-argon2
