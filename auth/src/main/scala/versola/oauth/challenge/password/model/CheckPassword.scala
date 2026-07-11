package versola.oauth.challenge.password.model

import java.time.Instant

enum CheckPassword:
  case Success, Failure
  case OldPassword(changedAt: Instant)
  /** The submitted password matches a non-expired temporary password; user must set a new one. */
  case Temporary
  /** The submitted password matches an expired temporary password; login is denied. */
  case TemporaryExpired

