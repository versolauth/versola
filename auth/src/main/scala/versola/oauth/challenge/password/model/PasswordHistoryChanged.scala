package versola.oauth.challenge.password.model

/** Another change of the same user's password committed between the reuse check and the insert, so the check
  * ran against a history that is no longer the current one. The caller checks again. */
case object PasswordHistoryChanged
