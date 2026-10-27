package versola.oauth.delivery

/** A message was to leave through a channel that has no server behind it when it came to it: none is configured, or the
  * saved one is switched off or cannot be read. Failing, instead of quietly doing nothing, is what lets a caller that
  * stored something for the message (a temporary password) take it back. */
final class ChannelUnavailable(channel: String) extends RuntimeException(s"The $channel channel is not configured or is switched off")
