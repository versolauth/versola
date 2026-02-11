package versola.util

import zio.schema.Schema

type Email = Email.Type

object Email:
  opaque type Type <: String = String
  
  inline def apply(string: String): Email = string
  
  def from(string: String): Either[String, Email] =
    if isValidEmail(string) then Right(string)
    else Left(s"$string is invalid email")
  
  private def isValidEmail(email: String): Boolean =
    val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
    emailRegex.matches(email)
  
  given Schema[Type] = Schema.primitive[String].transformOrFail(from, Right(_))