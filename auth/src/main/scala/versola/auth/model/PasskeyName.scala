package versola.auth.model

import zio.json.{JsonDecoder, JsonEncoder}
import zio.prelude.Equal
import zio.schema.Schema

type PasskeyName = PasskeyName.Type

object PasskeyName:
  opaque type Type <: String = String

  inline def apply(value: String): PasskeyName = value

  def from(value: String): Either[String, PasskeyName] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left("Passkey name must not be empty")
    else Right(trimmed)

  given Equal[PasskeyName] = Equal.make(_ == _)
  given Schema[PasskeyName] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[PasskeyName] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[PasskeyName] = JsonDecoder.string.mapOrFail(from)