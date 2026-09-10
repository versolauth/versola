package versola.loadgen.protocol

/** Open enum of known conversation step identifiers carried by
  * `<meta name="versola-step" content="...">` in the challenge HTML.
  *
  * Ported from `e2e/.../support/ConversationStep.scala` with the one fix
  * versola-loadgen-dev-spec.md §3.1/§3.3 calls out: the extraction regex is a top-level `val`
  * here, not recompiled on every page (the e2e original compiles it inside `fromHtml`, once per
  * call -- fine at test volume, a measurable cost at load).
  */
enum ConversationStep(val value: String):
  case Credential extends ConversationStep("credential")
  case Password extends ConversationStep("password")
  case SetPassword extends ConversationStep("set-password")
  case Otp extends ConversationStep("otp")
  case PasskeyEnroll extends ConversationStep("passkey-enroll")
  case Consent extends ConversationStep("consent")
  case AccessDenied extends ConversationStep("access-denied")
  case Unknown(override val value: String) extends ConversationStep(value)

object ConversationStep:
  private val known: List[ConversationStep] =
    List(Credential, Password, SetPassword, Otp, PasskeyEnroll, Consent, AccessDenied)

  private val stepMetaTag = """<meta name="versola-step" content="([^"]+)"""".r

  def fromString(s: String): ConversationStep =
    known.find(_.value == s).getOrElse(Unknown(s))

  def fromHtml(html: String): Option[ConversationStep] =
    stepMetaTag.findFirstMatchIn(html).map(m => fromString(m.group(1)))
