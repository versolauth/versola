package versola.oauth.client.model

import zio.json.JsonCodec

case class SystemSettingsRecord(
    passwordRegex: String,
) derives JsonCodec

object SystemSettingsRecord:
  val DefaultPasswordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  val default: SystemSettingsRecord =
    SystemSettingsRecord(
      passwordRegex = DefaultPasswordRegex,
    )
