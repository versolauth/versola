package versola.util

import com.google.i18n.phonenumbers.PhoneNumberUtil
import zio.schema.Schema

type Phone = Phone.Type

object Phone:
  given Schema[Phone] = Schema.primitive[String]
    .transformOrFail(parse, Right(_))
  
  private val util = PhoneNumberUtil.getInstance()
  private val regex = "\\+?\\d{9,15}".r

  opaque type Type <: String = String

  inline def apply(phone: String): Phone = phone

  def parse(string: String): Either[String, Phone] = {
    try {
      val isValid = regex.matches(string) && util.isValidNumber(util.parse(string, "ZZ"))
      Either.cond(isValid, Phone(string), s"$string is invalid phone number")
    } catch {
      case ex: com.google.i18n.phonenumbers.NumberParseException =>
        Left(ex.getMessage)
    }
  }
