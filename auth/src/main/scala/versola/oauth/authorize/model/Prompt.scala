package versola.oauth.authorize.model

/** OIDC `prompt` parameter values.
  * @see https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
  */
enum Prompt:
  /** Do not display any authentication or consent UI. */
  case none
  /** Force re-authentication even if the user has an active session. */
  case login
  /** Request consent even if previously granted. */
  case consent
  /** Prompt the user to select a user account. */
  case select_account

object Prompt:
  def fromString(s: String): Option[Prompt] =
    Prompt.values.find(_.toString == s)
