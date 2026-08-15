package versola.central.configuration.details

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

/** The value of the RFC 9396 `type` member of an authorization detail object. */
type AuthorizationDetailType = AuthorizationDetailType.Type

object AuthorizationDetailType:
  opaque type Type <: String = String

  inline def apply(string: String): AuthorizationDetailType = string

  def from(string: String): Either[String, AuthorizationDetailType] =
    if string.isEmpty then Left("Authorization detail type must not be empty")
    else Right(string)

  given Schema[AuthorizationDetailType] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[AuthorizationDetailType] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[AuthorizationDetailType] = JsonDecoder.string.mapOrFail(from)
