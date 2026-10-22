package versola.oauth.est

/** Why an EST request is not served, as the client is told (RFC 7030 §4.2.3: a suitable 4xx). */
enum EstError(message: String) extends RuntimeException(message):
  /** EST is off, or the route is not one this listener serves. */
  case Disabled extends EstError("EST is not enabled")

  /** The token or the presented certificate is not valid. From outside, which of the reasons is not told. */
  case Unauthorized extends EstError("the credentials are not valid")

  /** The request is not one that can be served: not a PKCS#10, or not what the client is registered for (a subject, a key). */
  case BadRequest(reason: String) extends EstError(reason)

  /** The client may not have a certificate now: EST is not enabled for it, or its chain has reached its limit. */
  case Forbidden(reason: String) extends EstError(reason)
