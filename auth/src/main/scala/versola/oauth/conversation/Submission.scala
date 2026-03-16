package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.user.model.Login
import versola.util.{Email, FormDecoder, Phone}
import zio.http.Form
import zio.schema.*

sealed trait Submission

case class PhoneSubmission(phone: Phone)
  extends Submission derives Schema

case class EmailSubmission(email: Email)
  extends Submission derives Schema

case class OtpResendSubmission()
  extends Submission derives Schema

case class OtpSubmission(code: OtpCode)
  extends Submission derives Schema

case class LoginPasswordSubmission(login: Login, password: Password)
  extends Submission derives Schema


