# OpenID conformance

Runs the [OpenID Foundation conformance suite](https://gitlab.com/openid/conformance-suite) against a
local Versola, unattended. Today that is the **FAPI 2.0 Security Profile (Final)** plan; the evidence
issue is [versola#486](https://github.com/versolauth/versola/issues/486).

| | |
|---|---|
| Plan | `fapi2-security-profile-final-test-plan` |
| Variant | `plain_fapi`, `private_key_jwt` client authentication, DPoP sender constraint, `openid_connect`, simple requests (override with `--variant key=value`) |
| Tenant | `fapi2`, security profile `fapi2`, two web clients (PS256 `private_key_jwt`, DPoP-bound tokens, PAR required), one user |

## What you need

- Docker, Python 3.9+ (the runner uses only the standard library).
- A running local stack (`develop.md`) whose Versola answers on `http://localhost:2821`.
- A **public https issuer** for it. The suite runs in docker and fetches the issuer's discovery
  document, JWKS and endpoints itself, and FAPI 2.0 wants an https issuer. `expose-local-stack.sh`
  does this with ngrok (`brew install ngrok`, `ngrok config add-authtoken …`).

## Run it

```bash
cd openid-conformance
ISSUER=$(./expose-local-stack.sh up | tail -1)   # tunnel + issuer rewrite + restart of central/auth/edge
./run.py all --issuer "$ISSUER"                  # suite up, provision, run every module
./expose-local-stack.sh down                     # put the stack back on http://localhost:2821
```

`all` is `suite up`, `provision` and `run`; each is a command of its own, and `suite down` stops the
suite (its data lives in the `versola-conformance_mongo-data` volume). A single module:
`./run.py run --issuer "$ISSUER" --only happy-flow`.

Exit status is 0 when every module passed or ended as recorded in `expected.json`, 1 otherwise.
Results go to `results/<timestamp>/`: `report.md` (the table and each deviation), `results.json`, and
the suite's full log of every module under `logs/` (the same logs the suite's web UI shows, at
`https://localhost:8443`). `.state/` holds the generated client keys and the test user's password;
both directories are git-ignored.

`expose-local-stack.sh up` edits the bundle's `auth.conf`, `central.conf` and `edge.conf` under
`~/.versola/active` (originals are kept in `~/.versola/active-backup-localhost` until `down`), so
other things using that stack see the ngrok issuer meanwhile. Central's admin API is reached through a
throwaway `curlimages/curl` container on the `versola-local_default` network (`--docker-network`,
`--central-container`); against another deployment pass `--central-url` and `--central-secret`.

## How a module runs

1. `provision` creates the tenant, a PS256 signing key for it, the clients and the user through
   central's admin API, and writes the plan's client keys to `.state/`. It is repeatable: it keeps
   what exists (`--reset` registers the clients again). Setting the user's password uses
   `/users/password/set`, which exists only outside production.
2. `run` creates the plan on the suite and starts each module in turn.
3. A module waits for "a person" to open the authorization URL. The runner plays that person
   (`Driver` in `run.py`): it opens the URL on `localhost:2821`, follows Versola's login
   (`/challenge/login-password`, then consent if asked), and, because the suite reads the URL
   fragment of its callback page with JavaScript, posts the fragment back to the suite by hand.
4. The log of the module is read for `FAILURE` and `WARNING` entries; a module that is not `PASSED`
   is looked up in `expected.json`.

`user-rejects-authentication` needs a person to refuse the login, so the runner does not run it
(reported as `manual`); do it in the suite's UI.

## Reading a result

`expected.json` records what is known and accepted, keyed by module: the result it ends in, the
suite conditions allowed to fail, and why. Anything else that is not `PASSED` is a **regression** and
makes the run exit 1. Each entry should be removed as soon as the cause is gone.

Two things in it come from the setup, not from Versola:

- `RequireOnlyBCP195…`: the suite checks the TLS ciphers of the issuer's host, which through ngrok
  are ngrok's. Behind our own TLS termination this check is real and the entry should go.
- The suite skips `test-claims-parameter-identity-claims` unless the plan carries a claims
  configuration.

## State of the work

First run: 2026-10-09 against a development build (47 modules: 41 passed, 4 failed, 1 warning, 1
skipped; the fixes it prompted were never merged).

Run of 2026-10-18 against the released **0.7.1**: 52 modules, 38 passed, 3 ended as `expected.json`
records (the two ngrok TLS-cipher checks and the claims module the plan cannot configure), 4 stop at a
request for a screenshot of an error page (`par-*`: the suite wants a person to confirm that
Versola showed an error for a reused, expired or foreign `request_uri`; the runner sees the
error but cannot sign it off), 1 needs a person (`user-rejects-authentication`), and **6 deviations**:

| Module | What the suite saw |
|---|---|
| `ensure-mismatched-dpop-jkt-fails`, `ensure-token-endpoint-fails-with-mismatched-dpop-proof-jkt` | A `dpop_jkt` at `/par` that differs from the key of the DPoP proof is accepted (201), and the mismatch is not caught at `/token` either |
| `ensure-response-type-code-idtoken-fails` | `response_type=code id_token` (hybrid) is served to a `fapi2` client, with the code in the fragment |
| `dpop-negative-tests` | `/userinfo` rejects a proof whose `htu` differs only in scheme/host case or carries the scheme's default port (RFC 9449 §4.3 asks for the RFC 3986 §6.2.2 normalisation) |
| `attempt-reuse-authorization-code-after-one-second` | After a second use of an authorization code, the access token issued from it still works at `/userinfo` (warning) |
| `refresh-token` | A refresh with a DPoP key other than the one the code was exchanged with is refused (`invalid_grant`); most likely the refresh token is bound to the first DPoP key, which RFC 9449 §5 does not do for a confidential client (cause not yet confirmed in the code) |

Not covered yet: the `mtls` sender constraint and `tls_client_auth` / `self_signed_tls_client_auth`
variants (the suite's mTLS endpoint is on port 8444 and needs client certificates the runner does not
yet provision), `signed_non_repudiation` (JAR + JARM), and the FAPI 2.0 Message Signing plan. Add a
variant by passing `--variant` and provisioning the matching client in `provision`.
