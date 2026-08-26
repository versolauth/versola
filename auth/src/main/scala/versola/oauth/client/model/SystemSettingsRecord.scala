package versola.oauth.client.model

import zio.json.JsonCodec

case class SystemSettingsRecord(
    passwordRegex: String,
    passwordHistorySize: Int,
    passwordNumDifferent: Int,
    identityProviderLogo: Option[String] = None,
) derives JsonCodec

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
