package versola.oauth.conversation.model

private[conversation] sealed trait Error

private[conversation] object Error:
  case object BadRequest extends Error
