package versola.oauth.conversation.otp.model

import versola.oauth.conversation.ConversationResult

enum SendOtpResult:
  case Success(fake: Boolean)
  case LimitsExceeded

