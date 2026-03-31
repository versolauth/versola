package versola.central.configuration.permissions

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type Permission = Permission.Type

object Permission:
  opaque type Type <: String = String

  inline def apply(string: String): Permission = string
  inline def from(string: String): Either[String, Permission] = Right(string)
  given Schema[Permission]      = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[Permission] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Permission] = JsonDecoder.string.map(Permission(_))

