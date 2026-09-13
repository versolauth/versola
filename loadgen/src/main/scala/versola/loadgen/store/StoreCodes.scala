package versola.loadgen.store

import versola.loadgen.model.*

/** The `SMALLINT` codes the enum-valued columns of V0001/V0002 are stored as.
  *
  * Written out pair by pair rather than taken from `ordinal`: a `vu_users` row outlives the
  * process that wrote it (a campaign runs for days across driver restarts and redeploys), so an
  * enum case inserted in the middle of its declaration would otherwise renumber every code
  * after it and silently reinterpret rows already on disk -- every `regular` user becoming
  * `light` is a change to the load being generated, not an error anything would report.
  *
  * The [[Codes]] constructor checks that the mapping covers every case of the enum exactly
  * once, so a case added to the model without a code here fails at class initialisation rather
  * than on whichever row happens to carry it.
  */
object StoreCodes:

  final class Codes[A] private[StoreCodes] (
      label: String,
      toCode: Map[A, Short],
      fromCode: Map[Short, A],
  ):
    def encode(value: A): Short = toCode(value)

    def decodeOption(code: Short): Option[A] = fromCode.get(code)

    /** For the read path, where an unknown code means the row was written by a build this one
      * cannot interpret -- a defect, not a value to fall back on.
      */
    def decode(code: Short): A =
      fromCode.getOrElse(code, throw IllegalStateException(s"Unknown $label code $code"))

  private def codes[A](label: String, all: Array[A], pairs: (A, Short)*): Codes[A] =
    val toCode = pairs.toMap
    require(
      toCode.size == pairs.size && all.forall(toCode.contains) && toCode.size == all.length,
      s"$label: every case must be mapped to exactly one distinct code",
    )
    val fromCode = pairs.map((value, code) => code -> value).toMap
    require(fromCode.size == pairs.size, s"$label: codes must be distinct")
    Codes(label, toCode, fromCode)

  val activityClass: Codes[ActivityClass] = codes(
    "activity_class",
    ActivityClass.values,
    ActivityClass.Heavy -> 0,
    ActivityClass.Regular -> 1,
    ActivityClass.Light -> 2,
    ActivityClass.Dormant -> 3,
  )

  val platform: Codes[Platform] = codes(
    "platform",
    Platform.values,
    Platform.Mobile -> 0,
    Platform.Web -> 1,
  )

  val credential: Codes[CredentialKind] = codes(
    "credential",
    CredentialKind.values,
    CredentialKind.Otp -> 0,
    CredentialKind.OtpPassword -> 1,
    CredentialKind.Passkey -> 2,
  )

  val role: Codes[UserRole] = codes(
    "role",
    UserRole.values,
    UserRole.RetailUser -> 0,
    UserRole.RetailBasic -> 1,
  )

  val userState: Codes[VirtualUserState] = codes(
    "state",
    VirtualUserState.values,
    VirtualUserState.Planned -> 0,
    VirtualUserState.Registered -> 1,
    VirtualUserState.Broken -> 2,
  )

  val sessionKind: Codes[SessionKind] = codes(
    "kind",
    SessionKind.values,
    SessionKind.MobileToken -> 0,
    SessionKind.WebCookie -> 1,
  )

  val measurementKind: Codes[MeasurementKind] = codes(
    "measurement kind",
    MeasurementKind.values,
    MeasurementKind.Step -> 0,
    MeasurementKind.Flow -> 1,
  )
