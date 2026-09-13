package versola.loadgen.protocol

/** The OTP a non-prod deployment issues: the first N digits of `1234567890`, N coming from the
  * tenant's challenge settings (auth's `OtpGenerationService` -- and design doc §6.3, which
  * requires the campaign environment to be confirmed as running in that mode rather than the
  * SUT being weakened for the emulator).
  *
  * The length is read from the provisioned challenge settings (`AdminClient`, track E) and
  * passed in; six digits is the current default, not a constant to hardcode.
  */
object Otp:
  private val digits = "1234567890"

  def nonProd(length: Int): String =
    digits.repeat(length / digits.length + 1).take(length)
