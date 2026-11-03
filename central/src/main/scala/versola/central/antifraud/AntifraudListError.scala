package versola.central.antifraud

/** Why auth would not do what the admin asked. */
enum AntifraudListError:
  /** The entry or a parameter is not valid; auth's reason. */
  case Invalid(message: String)

  /** There is no such entry. */
  case NotFound

  /** The request is outside the caller's scope (an entry of another tenant, a tenant admin on a global one); auth's reason. */
  case Forbidden(message: String)

  /** The settings are no longer at the version the change was made on; `current` is the version they are at now, when auth
    * said it. */
  case StaleVersion(message: String, current: Option[Long])
