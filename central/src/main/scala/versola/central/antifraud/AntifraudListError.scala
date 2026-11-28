package versola.central.antifraud

/** Why what the admin asked is not done. */
enum AntifraudListError:
  /** The entry, the settings or a parameter is not valid; the reason, which is auth's for what auth checks. */
  case Invalid(message: String)

  /** There is no such entry. */
  case NotFound

  /** The request is outside the caller's scope (an entry of another tenant, a tenant admin on a global one). */
  case Forbidden(message: String)

  /** The settings are no longer at the version the change was made on; `current` is the version they are at now. */
  case StaleVersion(message: String, current: Option[Long])
