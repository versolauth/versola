package versola.central.users

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type Login = Login.Type

object Login:
  opaque type Type <: String = String

  inline def apply(value: String): Login = value
  inline def from(value: String): Either[String, Login] = Right(value)

  given Schema[Login] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[Login] = JsonEncoder.string
  given JsonDecoder[Login] = JsonDecoder.string
