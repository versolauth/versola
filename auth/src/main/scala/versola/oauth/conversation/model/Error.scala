package versola.oauth.conversation.model

private[conversation] sealed trait Error extends Throwable

private[conversation] object Error:
  case object BadRequest extends Error

  case object ConversationExpired extends Error

  case object ServiceUnavailable extends Error
