package versola.oauth.conversation
import versola.auth.model.{OtpCode, Password}
import versola.user.model.Login
import versola.util.{Email, FormDecoder, Phone}
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

/** Entering the registration flow from the credential card with the entry credential. */
case class RegisterEmailSubmission(email: Email, csrf: String)
  extends Submission derives Schema

case class RegisterPhoneSubmission(phone: Phone, csrf: String)
  extends Submission derives Schema

case class OtpSubmission(code: OtpCode, csrf: String)
  extends Submission derives Schema

case class PasswordSubmission(password: Password, csrf: String)
  extends Submission derives Schema

case class LoginPasswordSubmission(login: Login, password: Password, csrf: String)
  extends Submission derives Schema

case class PasskeyAssertionSubmission(response: String, csrf: String)
  extends Submission derives Schema

case class PasskeyEnrollSubmission(response: String, name: String, csrf: String)
  extends Submission derives Schema

case class PasskeySkipSubmission(csrf: String)
  extends Submission derives Schema
  
case class SetPasswordSubmission(password: Password, csrf: String)
  extends Submission derives Schema