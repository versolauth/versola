package versola.oauth.conversation.model

import zio.json.JsonCodec
import zio.schema.*

/** Another way for a person to pass the step that asks for a one-time code, offered on the code page next to the code.
  *
  * Two kinds are meant to fit here: another *factor* in place of the code (`Passkey`), and, later, another *channel*
  * that carries the same code to the same number (a messenger), which would be further cases of this enum. What the
  * page offers is computed whenever the step is rendered ([[versola.oauth.conversation.CodeAlternativeService]]) and
  * checked again when one is chosen; it is never stored on the step and never taken from the client.
  */
enum CodeAlternative derives Schema:
  /** A passkey of the user the conversation identifies, in place of the code. */
  case Passkey

  /** How the alternative is spelled on the wire: in the form field that chooses it and in the step the form is given. */
  def wire: String = this match
    case Passkey => "passkey"

object CodeAlternative:
  def parse(value: String): Either[String, CodeAlternative] =
    values.find(_.wire == value).toRight(s"unknown code alternative: $value")

  given JsonCodec[CodeAlternative] = JsonCodec.string.transformOrFail(parse, _.wire)
