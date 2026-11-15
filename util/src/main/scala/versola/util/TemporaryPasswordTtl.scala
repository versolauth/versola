package versola.util

/** How long the temporary password of an administrator's reset may live. Central refuses a request outside the range and auth
  * checks it again, so a caller that reaches auth without central cannot park a password for years or hand it a lifetime that
  * overflows the date arithmetic.
  */
object TemporaryPasswordTtl:
  val MinSeconds: Long = 60L * 60

  val MaxSeconds: Long = 7L * 24 * 60 * 60

  /** What auth uses when a request names no lifetime. */
  val DefaultSeconds: Long = 12L * 60 * 60

  def isValid(seconds: Long): Boolean = seconds >= MinSeconds && seconds <= MaxSeconds

  /** Names the field and the range, never a value, so it is safe in a response. */
  val violation: String =
    s"expiresInSeconds must be between $MinSeconds and $MaxSeconds (1 hour to 7 days)"
