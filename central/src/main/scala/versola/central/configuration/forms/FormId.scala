package versola.central.configuration.forms

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type FormId = FormId.Type

object FormId:
  opaque type Type <: String = String

  inline def apply(string: String): FormId = string
  inline def from(string: String): Either[String, FormId] = Right(string)
  given Schema[FormId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[FormId] = JsonDecoder.string.map(FormId(_))
  given JsonEncoder[FormId] = JsonEncoder.string.contramap(identity[String])
