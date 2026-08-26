package versola.central.configuration.roles

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type RoleId = RoleId.Type

object RoleId:
  opaque type Type <: String = String

  inline def apply(string: String): RoleId = string

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Role ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(string: String): Either[String, RoleId] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[RoleId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[RoleId] = JsonEncoder.string
  given JsonDecoder[RoleId] = JsonDecoder.string.mapOrFail(from)
