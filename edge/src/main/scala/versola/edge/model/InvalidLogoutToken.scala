package versola.edge.model

/** The `logout_token` presented to the back-channel logout endpoint failed one of the
  * validations of OIDC Back-Channel Logout §2.6.
  */
case class InvalidLogoutToken(reason: String)
