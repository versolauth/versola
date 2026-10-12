package versola.e2e.support

/** Known ACR (Authentication Context Class Reference) values registered in the
  * shared e2e bootstrap vocabulary (see [[Flows.layer]]).
  */
object Acr:
  /** Satisfied by an OTP factor. */
  val OtpLevel = "otp-level"

  /** Satisfied by a password factor. */
  val PasswordLevel = "password-level"

  /** Satisfied by a passkey factor. */
  val PasskeyLevel = "passkey-level"

  /** Reserved by Versola (#634): change the user's phone number. Defined by the server, so
    * not part of the tenant vocabulary above. */
  val PhoneChange = "urn:versola:acr:phone-change"

  /** Reserved by Versola (#634): change the user's email. */
  val EmailChange = "urn:versola:acr:email-change"
