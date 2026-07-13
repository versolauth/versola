package versola.oauth.client.model

import zio.json.JsonCodec

case class SystemSettingsRecord(
    passwordRegex: String,
    passwordHistorySize: Int,
    passwordNumDifferent: Int,
) derives JsonCodec

object SystemSettingsRecord:
  val DefaultPasswordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  val default: SystemSettingsRecord =
    SystemSettingsRecord(passwordRegex = DefaultPasswordRegex, passwordHistorySize = 5, passwordNumDifferent = 3)
