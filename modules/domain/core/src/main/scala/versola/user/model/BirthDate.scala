package versola.user.model

import zio.schema.Schema

import java.time.LocalDate
import java.time.format.DateTimeParseException

type BirthDate = BirthDate.Type

object BirthDate:
  opaque type Type <: LocalDate = LocalDate
  
  inline def apply(date: LocalDate): BirthDate = date
  
  def from(string: String): Either[String, BirthDate] =
    try Right(LocalDate.parse(string))
    catch
      case _: DateTimeParseException => Left(s"$string is invalid birth date")


  given Schema[Type] = Schema.primitive[String].transformOrFail(from, value => Right(value.toString))
