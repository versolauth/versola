package versola.email.model

import zio.schema.Schema

type EmailAddress = EmailAddress.Type

object EmailAddress:
  opaque type Type <: String = String
  
  inline def apply(address: String): EmailAddress = address
  
  def from(address: String): Either[String, EmailAddress] =
    if isValidEmail(address) then Right(address)
    else Left(s"$address is invalid email address")
  
  private def isValidEmail(email: String): Boolean =
    val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
    emailRegex.matches(email)
  
  given Schema[Type] = Schema.primitive[String].transformOrFail(from, Right(_))
