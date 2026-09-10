package versola.loadgen.protocol

import zio.http.Status

/** Why a refresh exchange was rejected. Reuse detection (`RefreshRejected(AlreadyExchanged)`)
  * is the one that must stay at ~0 for the whole campaign -- see §7.4 and
  * `loadgen_refresh_rejected_total` in §11. Any sustained non-zero value means either a
  * scheduling bug (two fibers touching one session) or a real SUT defect, and either way the
  * campaign's numbers past that point are not trustworthy.
  */
enum RefreshRejection:
  case AlreadyExchanged
  case SessionRevoked
  case Expired
  case Unknown(detail: String)

/** Every way a protocol call can end up not returning the happy-path result. Deliberately not
  * just `Throwable`: `StepUpRequired`/`Forbidden`/`Unauthorized` are *expected outcomes* the
  * scenario engine branches on (§4, §7.4), not failures, and keeping them out of a generic
  * error channel is what stops them from being miscounted as SUT errors in the report (§11).
  */
enum ProtocolError:
  /** Connect/read/timeout -- infrastructure, not the SUT under test. */
  case Transport(cause: Throwable)
  case UnexpectedStatus(expected: Set[Status], got: Status, endpoint: String)
  case MalformedResponse(endpoint: String, detail: String)
  /** `401 WWW-Authenticate: ...insufficient_user_authentication, acr_values="..."` -- expected,
    * drives the step-up re-authentication flow (§7.4).
    */
  case StepUpRequired(acrValues: List[String], endpoint: String)
  /** Expected for `retail-basic` users on actions their role doesn't cover. */
  case Forbidden(endpoint: String)
  /** Expected once an access token's TTL (§5's `session.access-token-ttl`) has elapsed. */
  case Unauthorized(endpoint: String)
  case RefreshRejected(reason: RefreshRejection)
