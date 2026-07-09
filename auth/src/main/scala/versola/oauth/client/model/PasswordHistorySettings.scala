package versola.oauth.client.model

case class PasswordHistorySettings(historySize: Int, numDifferent: Int)

object PasswordHistorySettings:
  val default: PasswordHistorySettings = PasswordHistorySettings(historySize = 5, numDifferent = 3)
