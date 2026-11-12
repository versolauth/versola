package versola.central.configuration.system

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** Global, non-tenant-scoped settings. The password policy lives here because
  * user password credentials are stored globally (per user, not per tenant).
  */
case class SystemSettingsRecord(
    passwordRegex: String,
    passwordHistorySize: Int,
    passwordNumDifferent: Int,
    /** The time zone the admin console shows times in for an administrator with no `zoneinfo` of their own: a name of the IANA time zone
      * database. Absent: the console uses the browser's zone. */
    uiTimeZone: Option[String],
) derives Schema, JsonCodec

object SystemSettingsRecord:
  val DefaultPasswordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  // CIAM default: minimal history check, mainly to stop "change it back immediately" resets.
  // Deployments needing a stricter policy (e.g. internal employee auth) can override via
  // SystemSettingsController's upsert endpoint.
  val default: SystemSettingsRecord =
    SystemSettingsRecord(
      passwordRegex = DefaultPasswordRegex,
      passwordHistorySize = 2,
      passwordNumDifferent = 1,
      uiTimeZone = None,
    )
