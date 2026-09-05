package versola.central.configuration.resources

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ResourceId = ResourceId.Type

object ResourceId:
  opaque type Type <: String = String

  inline def apply(value: String): ResourceId = value
  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Resource ID must start with a lowercase Latin letter and contain only lowercase Latin letters, numbers, and hyphens"

  def from(value: String): Either[String, ResourceId] =
    if validPattern.matches(value) then Right(value) else Left(invalidMessage)

  def isValid(value: ResourceId): Boolean = validPattern.matches(value)

  given Schema[ResourceId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[ResourceId] = JsonEncoder.string
  given JsonDecoder[ResourceId] = JsonDecoder.string.mapOrFail(from)