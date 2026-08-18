package versola.e2e.support

import zio.*

/** Open enum of known conversation step identifiers carried by
  * `<meta name="versola-step" content="...">` in the challenge HTML.
  *
  * `Unknown` captures any value not yet listed here so tests written against
  * the current set of steps continue to compile when new steps are added.
  */
enum ConversationStep(val value: String):
  case Credential   extends ConversationStep("credential")
  case Password     extends ConversationStep("password")
  case SetPassword  extends ConversationStep("set-password")
  case Otp          extends ConversationStep("otp")
  case PasskeyEnroll extends ConversationStep("passkey-enroll")
  case Consent      extends ConversationStep("consent")
  case AccessDenied extends ConversationStep("access-denied")
  case Unknown(override val value: String) extends ConversationStep(value)

object ConversationStep:
  private val known: List[ConversationStep] =
    List(Credential, Password, SetPassword, Otp, PasskeyEnroll, Consent, AccessDenied)

  def fromString(s: String): ConversationStep =
    known.find(_.value == s).getOrElse(Unknown(s))

  def fromHtml(html: String): Option[ConversationStep] =
    """<meta name="versola-step" content="([^"]+)"""".r
      .findFirstMatchIn(html)
      .map(m => fromString(m.group(1)))

extension (step: Option[ConversationStep])
  def assertIs(expected: ConversationStep): Task[ConversationStep] =
    step match
      case Some(s) if s == expected => ZIO.succeed(s)
      case Some(s)                  => ZIO.fail(RuntimeException(s"Expected step='${expected.value}', got '${s.value}'"))
      case None                     => ZIO.fail(RuntimeException(s"Expected step='${expected.value}', but no versola-step meta tag found"))
