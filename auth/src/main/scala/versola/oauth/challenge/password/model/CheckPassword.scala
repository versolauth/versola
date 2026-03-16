package versola.oauth.challenge.password.model

import java.time.Instant

enum CheckPassword:
  case Success, Failure
  case OldPassword(changedAt: Instant)

