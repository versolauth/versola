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
) derives Schema, JsonCodec

object SystemSettingsRecord:
  val DefaultPasswordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  val default: SystemSettingsRecord =
    SystemSettingsRecord(passwordRegex = DefaultPasswordRegex, passwordHistorySize = 5, passwordNumDifferent = 3)
