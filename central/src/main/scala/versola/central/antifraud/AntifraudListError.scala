package versola.central.antifraud

/** Why auth would not do what the admin asked. */
enum AntifraudListError:
  /** The entry or a parameter is not valid; auth's reason. */
  case Invalid(message: String)

  /** There is no such entry. */
  case NotFound
