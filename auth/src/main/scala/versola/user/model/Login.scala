package versola.user.model

import zio.schema.Schema

type Login = Login.Type

object Login:
  opaque type Type <: String = String

  inline def apply(string: String): Login = string
  inline def from(string: String): Either[String, Login] = Right(string)
  given Schema[Login] = Schema.primitive[String].transformOrFail(from, Right(_))
