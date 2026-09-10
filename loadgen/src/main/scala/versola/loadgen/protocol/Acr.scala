package versola.loadgen.protocol

/** Known ACR (Authentication Context Class Reference) values, provisioned by track E
  * (`AdminClient.upsertAuthRequestPresets`/challenge settings) and asserted against by every
  * step-up flow (§7.4). Copied verbatim from `e2e/.../support/Acr.scala` -- see
  * versola-loadgen-dev-spec.md §3.1, "Copy verbatim".
  */
object Acr:
  /** Satisfied by an OTP factor. */
  val OtpLevel = "otp-level"

  /** Satisfied by a password factor. */
  val PasswordLevel = "password-level"

  /** Satisfied by a passkey factor. */
  val PasskeyLevel = "passkey-level"
