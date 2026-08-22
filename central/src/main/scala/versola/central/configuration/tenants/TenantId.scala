package versola.central.configuration.tenants

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type TenantId = TenantId.Type

object TenantId:
  opaque type Type <: String = String

  inline def apply(value: String): TenantId = value

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Tenant ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(value: String): Either[String, TenantId] =
    if validPattern.matches(value) then Right(value) else Left(invalidMessage)

  given Schema[TenantId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[TenantId] = JsonEncoder.string
  given JsonDecoder[TenantId] = JsonDecoder.string.mapOrFail(from)

