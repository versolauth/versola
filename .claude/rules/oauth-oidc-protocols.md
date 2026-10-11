---
paths: ["auth/**", "edge/**", "central/src/main/**/clients/**", "central/src/main/**/challenges/**", "**/open-api/*.yaml"]
---

# OAuth 2.0 / OIDC Protocol Rules

`auth` is an OAuth 2.0 / OpenID Connect provider; `edge` is a resource-side proxy that validates
what `auth` issues. A protocol change is judged against the standard's text, not against how the
code looks.

## Where the supported standards are listed

The requirement-by-requirement checklists live in the separate `versolauth/versola-website`
repository (GitHub), one page per standard, in `src/content/docs/rfc/`. It is not part of this
checkout; if it is not available locally, read it on GitHub. Each line states a
requirement, its section, whether Versola enforces it, and the test that proves it. Read the page
of every standard your change touches before you edit, and treat it as the statement of what
the product promises.

| Page | Standard |
|---|---|
| `rfc6749` | OAuth 2.0 |
| `rfc7009` | Token Revocation |
| `rfc7517`, `rfc7519` | JWK, JWT |
| `rfc7636` | PKCE |
| `rfc7662` | Token Introspection |
| `rfc8176` | Authentication Method Reference values |
| `rfc8414` | Authorization Server Metadata |
| `rfc8705` | Mutual TLS, certificate-bound tokens |
| `rfc8707` | Resource Indicators |
| `rfc9101` | JWT-Secured Authorization Request (JAR) |
| `rfc9126` | Pushed Authorization Requests (PAR) |
| `rfc9396` | Rich Authorization Requests |
| `rfc9449` | DPoP |
| `rfc9470` | Step-up authentication |
| `oidc-core` | OpenID Connect Core |
| `jarm` | JWT Secured Authorization Response Mode |
| `fapi2` | FAPI 2.0 Security Profile |
| `oauth21-draft` | OAuth 2.1 (draft) |

The code and the specs under `*/open-api/` cite further standards (RFC 7523 client assertions,
RFC 9207 `iss`, RFC 6750, RFC 7591, RFC 8252, RFC 9700, ...). A standard that is on neither list
nor in the code is not supported; do not assume it is.

## Read the spec, do not recall it

- Before changing protocol behaviour, open the RFC or spec section (datatracker.ietf.org,
  openid.net/specs). Never quote a section number, a `MUST`, an error code or a parameter name
  from memory; a wrong citation in a comment or OpenAPI spec is worse than none.
- Follow the repo's citation style: a comment names the section and the reason, e.g.
  `// RFC 7009 §2.2: a value this server could never have issued is not reported as an error`.
- If the code and the standard disagree, say so; do not silently pick one. A deliberate departure
  (stricter or looser than the standard) is commented where it happens.

## Security profiles

A tenant has a security profile, `standard` or `fapi2` (FAPI 2.0 Security Profile; the default for
new tenants). How strict a client is held follows from its **tenant's profile**, not from a
per-client switch. For example `plain` PKCE is accepted under `standard` and refused under `fapi2`.
When you add or change an authorize / token / client-registration rule:

- decide explicitly what it does under each profile, and test both;
- a rule that exists for `fapi2` must hold whatever a client setting says;
- registration/patch validation (`central`) and runtime enforcement (`auth`) must agree: a client
  `central` accepts and `auth` then refuses is a bug.

The `fapi2` page lists exactly which requirements are enforced at registration and at runtime.
Loosening anything for `fapi2` is a security change (CLAUDE.md "Ask Before").

## Properties to keep

Established by the current code; the checklists above have the rest.

- `redirect_uri` is matched as an exact string against the client's registered URIs. A mismatch is a
  plain 400 shown to the user agent, never a redirect to the supplied URI.
- Replay is rejected: PAR `request_uri`s are consumed on read, DPoP proof and client-assertion
  `jti`s are recorded, authorization codes are deleted on use.
- Secrets, MACs and codes are compared with `MessageDigest.isEqual`; JWT verifiers take an explicit
  allow-list of algorithms instead of trusting the token header. No home-made crypto or JWT code.
- Client-facing errors follow the endpoint's standard form and do not leak internals; operator
  detail goes to `Observability.setError` (`observability.md`).

## Network and deployment facts that are easy to get wrong

- **mTLS has two paths into `auth`; keep them apart.**
  - *Direct:* `auth` serves its own RFC 8705 §5 listener on `MPORT`, terminates TLS itself and
    demands a client certificate in the handshake. It runs only when the `mutual-tls` config block
    (`certificate`, `private-key`, `trusted-certificates`, `external-url`) is present. `edge`'s
    native-app endpoints use it. Only the leaf certificate reaches `auth`, so the issuing CA must be in
    `trusted-certificates`.
  - *Behind a proxy:* a terminator in front of `auth` forwards the certificate in a header, whose
    encoding is configured per tenant (`MtlsCertificateEncoding`). Treat that header as trusted only
    when the terminator sets it; code must never accept it from a request that could have come
    straight from a client. (`develop.md` explains the local nginx terminator.)
  A change to certificate handling is checked on both paths. Details: `develop.md` "HTTP Server" and
  `rfc8705` checklist.
- **Public vs internal URL:** `edge` has `versolaUrl` (what a browser and the token `iss` use) and
  `versolaInternalUrl` / `internalUrl` (the real network call to `auth`). Mixing them up does not
  fail loudly; it 404s or refuses connections partway through a login. In containers, a browser
  URL is the published port, never a Compose service name.
- `central` is reached only through `edge`; its admin API is not public.

## When a protocol change is done

Spec citation in a comment, unit test (both profiles where relevant), e2e test across
`auth`/`edge`/`central` (CLAUDE.md "Test Coverage Rules"), `open-api` YAML and discovery metadata
updated (`openapi-specs.md`), the standard's checklist page in `versola-website` updated if a
requirement or its test changed, and the PR says which standard section the change follows.
