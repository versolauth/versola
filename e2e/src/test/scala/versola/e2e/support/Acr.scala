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
