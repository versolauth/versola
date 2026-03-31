package versola.central.configuration.tenants

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type TenantId = TenantId.Type

object TenantId:
  opaque type Type <: String = String

  inline def apply(value: String): TenantId = value
  inline def from(value: String): Either[String, TenantId] = Right(value)

  given Schema[TenantId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[TenantId] = JsonEncoder.string
  given JsonDecoder[TenantId] = JsonDecoder.string

