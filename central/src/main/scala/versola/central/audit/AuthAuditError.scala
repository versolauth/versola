package versola.central.audit

/** Why auth did not serve a per-user audit endpoint (its contract: `GET /audit/events?userId=` and `.../summary`). */
enum AuthAuditError:
  /** Auth refused the window or the cursor (400): the caller's request is wrong, not auth. */
  case Invalid(message: String)

  /** Auth did not answer: it is down, answered 5xx, or does not have the endpoint yet (404, an older release during a rolling
    * upgrade). The report is served without auth's part and says so. */
  case Unavailable(reason: String)
