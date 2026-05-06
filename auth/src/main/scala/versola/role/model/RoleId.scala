package versola.role.model

import zio.schema.Schema

type RoleId = RoleId.Type

object RoleId:
  opaque type Type <: String = String

  inline def apply(string: String): RoleId = string
  inline def from(string: String): Either[String, RoleId] = Right(string)
  given Schema[RoleId] = Schema.primitive[String].transformOrFail(from, Right(_))

