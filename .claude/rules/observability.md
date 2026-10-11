---
paths: ["**/*.scala"]
---

# Logging and Error Context

Request logging is built around `Observability` (`util/.../http/Observability.scala`). A server
middleware emits **one `receive-http` line per request** (logger `versola.http.HttpServer`) with the
request, response, timing, and two accumulated annotations. Enrich that line; do not add more lines.

## The request log context

- `auth` annotation (`AuthDetails`): who and what the request is about. Fill it in as soon as a
  value is known, from anywhere in the call stack: `Observability.setAuth`, `setClientId`,
  `setUserId`, `setSessionId`, `setStep`, `setToken`, ... The context is reset when the request
  ends, so it never leaks into another request. A new correlating field becomes a new
  `AuthDetails` member and `setX`, not an ad-hoc log message.
- `error` annotation (`ErrorDetails`, the "error context"): the outcome of a request that failed.
  Set it with `Observability.setError(code, description = None)`.

## Every failed outcome sets the error context

In `auth` (and equally in `edge` and `central`), every exceptional outcome ends with a
`setError` call at the place the decision is made, whatever the HTTP result:

- **3xx**: an error returned by redirect (`error=` in the redirect to the client, for instance
  `AuthorizeEndpointController`'s `RedirectError` branch) is still a failure. Call `setError`
  before building the redirect.
- **4xx**: rejected input, failed client authentication, `invalid_token`, `step_mismatch`,
  `csrf_mismatch`, rate limits, denied access.
- **5xx and "should not happen" states**: `illegal_state`, `write_conflict`, a dependency that is
  unavailable. An unexpected `Throwable` that reaches `Observability.handleErrors` is recorded
  with its cause automatically and logged at error level with the stack trace; a controller with
  its own error handler must record the cause the same way (see `ConversationController`).
- When you add a branch that returns an error response, adding its `setError` is part of that
  branch. When you touch a controller, check its other error branches for a missing one.

`code` is a stable, machine-readable key from a **closed vocabulary**: an OAuth error code
(`invalid_client`), a `stepErrorKey`, or a short snake_case reason (`write_conflict`). It feeds
alerts and metrics, so never build it from user input, ids, or messages. Reuse an existing
code before inventing one.

`description` is optional free text for what the code alone does not say. It is for operators
reading the log, so it may be more detailed than what the client is told (that is why error
types carry `logDescription` apart from `errorDescription`). It must not contain secrets,
tokens, passwords, OTPs, client secrets, or personal data (emails, phone numbers). Refresh
tokens appear only through `setRefreshToken`, which keeps a short prefix.

## Adding log lines

The log volume is a cost; keep it low.

- Do not log in a success path, in a loop or per item, or in anything on the per-request hot path
  (token validation, cache reads). Do not log "entering X" or "X done".
- Do not log and rethrow or return an error: the failure is already reported once, by
  `setError` or by the middleware. Logging it again duplicates it.
- A separate `ZIO.log*` is for events that no HTTP request owns: background work, outbox
  delivery, startup, a failure you deliberately swallow (`ZIO.logWarningCause` with what was
  dropped and why). Choose the level honestly: `Warning` for degraded but handled, `Error` for
  needs attention. Rate-limit or aggregate anything an attacker can trigger repeatedly.
- Never log request or response bodies, `Authorization` or cookie values, or key material.
  Query parameters and headers are logged only if allow-listed in `HttpObservabilityConfig.Server`;
  adding to that list needs a reason, and the same secrets rules apply.
- Metric labels have the same cardinality rule as `code`: only closed sets.
