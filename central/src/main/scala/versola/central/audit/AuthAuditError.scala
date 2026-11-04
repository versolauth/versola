package versola.central.audit

/** Why auth did not serve a per-user audit endpoint (its contract: `GET /users/audit-events?id=` and `GET /users/audit-summary?id=`). */
enum AuthAuditError:
  /** Auth refused the window or the cursor (400): the caller's request is wrong, not auth. */
  case Invalid(message: String)

  /** Auth did not answer: it is down or answered 5xx. The report is served without auth's part and says so. */
  case Unavailable(reason: String)

  /** Auth has no such endpoint (404: an older release during a rolling upgrade, or a path that does not match). Served like
    * [[Unavailable]], and counted apart, since an outage passes and this does not. */
  case EndpointMissing
