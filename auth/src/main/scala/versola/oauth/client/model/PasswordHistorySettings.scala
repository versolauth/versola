package versola.oauth.client.model

case class PasswordHistorySettings(historySize: Int, numDifferent: Int)

object PasswordHistorySettings:
  // CIAM default: minimal history check, mainly to stop "change it back immediately" resets.
  // Deployments needing a stricter policy (e.g. internal employee auth) can override via
  // SystemSettingsController's upsert endpoint.
  val default: PasswordHistorySettings = PasswordHistorySettings(historySize = 2, numDifferent = 1)
