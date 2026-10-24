package versola.central.antifraud

/** Why auth would not do what the admin asked, or why central would not ask. */
enum AntifraudListError:
  /** The entry or a parameter is not valid; auth's reason. */
  case Invalid(message: String)

  /** The admin may not change or read that part of the lists; auth's reason. */
  case Forbidden(message: String)

  /** There is no such entry. */
  case NotFound

  /** The request names no admin: it did not come through edge. */
  case NoCaller
