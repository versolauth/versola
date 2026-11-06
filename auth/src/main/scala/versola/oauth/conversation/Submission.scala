package versola.oauth.conversation
import versola.auth.model.{OtpCode, PasskeyName, Password}
import versola.oauth.client.model.ScopeToken
import versola.user.model.Login
import versola.util.{Email, Phone}
import zio.http.Form
import zio.schema.*

/** What a request for a code says about the proof of work a challenge may ask of it. The forms that can solve one send
  * their submit from script (`proof_inline`), and answer a challenge by sending the same submit again with the token
  * and its solution (`proof_token`, `proof_solution`); a plain form post cannot, and is refused if a challenge is due.
  */
enum ProofInput derives Schema:
  case NoScript
  case Asked
  case Answered(token: String, solution: String)

object ProofInput:
  val InlineField = "proof_inline"
  val TokenField = "proof_token"
  val SolutionField = "proof_solution"

sealed trait Submission:
  def csrf: String

/** A submission that asks for a code to be sent, and so may be asked for a proof first. */
sealed trait CodeRequest extends Submission:
  def proof: ProofInput

case class PhoneSubmission(phone: Phone, proof: ProofInput, csrf: String)
  extends CodeRequest derives Schema

case class EmailSubmission(email: Email, proof: ProofInput, csrf: String)
  extends CodeRequest derives Schema

case class OtpResendSubmission(proof: ProofInput, csrf: String)
  extends CodeRequest derives Schema

case class OtpSubmission(code: OtpCode, csrf: String)
  extends Submission derives Schema

case class PasswordSubmission(password: Password, csrf: String)
  extends Submission derives Schema

case class LoginPasswordSubmission(login: Login, password: Password, csrf: String)
  extends Submission derives Schema

case class PasskeyAssertionSubmission(response: String, csrf: String)
  extends Submission derives Schema

case class PasskeyEnrollSubmission(response: String, name: PasskeyName, csrf: String)
  extends Submission derives Schema

case class PasskeySkipSubmission(csrf: String)
  extends Submission derives Schema

/** The browser could not produce a passkey for a passkey-only step: none on this device, or the prompt was
  * dismissed. Nothing else is offered in its place, so the conversation is denied. */
case class PasskeyUnavailableSubmission(csrf: String)
  extends Submission derives Schema

case class SetPasswordSubmission(password: Password, csrf: String)
  extends Submission derives Schema

/** The scope the user left selected on the consent screen. Always carries the full granted set,
  * including the non-deselectable tokens, so the server validates exactly what was displayed. */
case class ConsentAllowSubmission(scope: Set[ScopeToken], csrf: String)
  extends Submission derives Schema

case class ConsentDenySubmission(csrf: String)
  extends Submission derives Schema
