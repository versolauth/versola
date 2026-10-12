package versola.user

import versola.util.{Email, Phone}
import zio.json.*

/** Something a signed-in user asked for on the account page, to be carried out by a later request
  * that must find it there (see [[UserIntentRepository]]). Each case is one kind of request; adding
  * one adds a case here, not a column.
  */
@jsonDiscriminator("type")
sealed trait UserIntent derives JsonCodec

object UserIntent:
  /** Change the user's phone number to `phone`, once the user has proven the new one. */
  @jsonHint("change_phone")
  case class ChangePhone(phone: Phone) extends UserIntent

  /** Change the user's email to `email`, once the user has proven the new one. */
  @jsonHint("change_email")
  case class ChangeEmail(email: Email) extends UserIntent

  def change(credential: Either[Email, Phone]): UserIntent = credential match
    case Left(email) => ChangeEmail(email)
    case Right(phone) => ChangePhone(phone)
