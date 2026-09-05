package versola.central.configuration.permissions

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type Permission = Permission.Type

object Permission:
  opaque type Type <: String = String

  inline def apply(string: String): Permission = string

  private val validPattern = "^[a-z][a-z0-9_]*([.:][a-z][a-z0-9_]*)*$".r
  private val invalidMessage =
    "Permission must be lowercase letters, numbers and underscores, with segments separated by '.' or ':', each starting with a letter"

  def from(string: String): Either[String, Permission] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[Permission]      = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[Permission] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Permission] = JsonDecoder.string.mapOrFail(from)

