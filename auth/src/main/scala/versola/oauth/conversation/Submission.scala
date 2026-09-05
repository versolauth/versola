package versola.oauth.conversation
import versola.auth.model.{OtpCode, PasskeyName, Password}
import versola.oauth.client.model.ScopeToken
import versola.user.model.Login
import versola.util.{Email, Phone}
import zio.http.Form
import zio.schema.*

sealed trait Submission:
  def csrf: String

case class PhoneSubmission(phone: Phone, csrf: String)
  extends Submission derives Schema

case class EmailSubmission(email: Email, csrf: String)
  extends Submission derives Schema

case class OtpResendSubmission(csrf: String)
  extends Submission derives Schema

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
  
case class SetPasswordSubmission(password: Password, csrf: String)
  extends Submission derives Schema

/** The scope the user left selected on the consent screen. Always carries the full granted set,
  * including the non-deselectable tokens, so the server validates exactly what was displayed. */
case class ConsentAllowSubmission(scope: Set[ScopeToken], csrf: String)
  extends Submission derives Schema

case class ConsentDenySubmission(csrf: String)
  extends Submission derives Schema