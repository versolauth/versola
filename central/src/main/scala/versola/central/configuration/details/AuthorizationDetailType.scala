package versola.central.configuration.details

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

/** The value of the RFC 9396 `type` member of an authorization detail object. */
type AuthorizationDetailType = AuthorizationDetailType.Type

object AuthorizationDetailType:
  opaque type Type <: String = String

  inline def apply(string: String): AuthorizationDetailType = string

  private val validPattern = "^[a-z][a-z0-9_]*$".r
  private val invalidMessage =
    "Authorization detail type must start with a lowercase letter and contain only lowercase letters, numbers and underscores"

  def from(string: String): Either[String, AuthorizationDetailType] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[AuthorizationDetailType] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[AuthorizationDetailType] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[AuthorizationDetailType] = JsonDecoder.string.mapOrFail(from)
