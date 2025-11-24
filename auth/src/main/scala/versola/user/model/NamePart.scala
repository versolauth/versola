package versola.user.model

import zio.schema.Schema

trait NamePart:
  opaque type Type <: String = String
  inline def apply(string: String): Type = string
  inline def from(string: String): Either[String, Type] = NamePart.validate(string)
  given Schema[Type] = Schema.primitive[String].transformOrFail(from, Right(_))

object NamePart:
  /* Только символы кириллицы, латиницы, пробелы, дефисы. Длина от 2 до 30 символов */
  val regex = """^[a-zA-Zа-яА-ЯёЁ\s-]{2,30}$""".r

  private def validate(string: String): Either[String, String] =
    if regex.matches(string) then Right(string)
    else Left(s"$string is invalid name part")

