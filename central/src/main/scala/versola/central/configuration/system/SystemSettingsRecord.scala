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
    identityProviderLogo: Option[String] = None,
) derives Schema, JsonCodec

object SystemSettingsRecord:
  val DefaultPasswordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  // CIAM default: minimal history check, mainly to stop "change it back immediately" resets.
  // Deployments needing a stricter policy (e.g. internal employee auth) can override via
  // SystemSettingsController's upsert endpoint.
  val default: SystemSettingsRecord =
    SystemSettingsRecord(
      passwordRegex        = DefaultPasswordRegex,
      passwordHistorySize  = 2,
      passwordNumDifferent = 1,
      identityProviderLogo = None,
    )
