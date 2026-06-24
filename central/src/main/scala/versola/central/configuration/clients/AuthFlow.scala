package versola.central.configuration.clients

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}
import zio.prelude.Equal
import zio.schema.*

enum PrimaryCredential derives JsonCodec, Schema, Equal:
  case email, phone, login

enum AuthFactorType derives JsonCodec, Schema, Equal:
  case otp, password, passkeyEnroll

/** An authentication challenge that the user can pass, used as the key/value type of the
  * `equivalents` map. Mirrors the auth service's `PassedAuthFactor`.
  */
enum PassedAuthFactor derives JsonCodec, Schema, Equal:
  case otp, password, passkey

object PassedAuthFactor:
  given JsonFieldEncoder[PassedAuthFactor] = JsonFieldEncoder.string.contramap(_.toString)
  given JsonFieldDecoder[PassedAuthFactor] =
    JsonFieldDecoder.string.mapOrFail(s => PassedAuthFactor.values.find(_.toString == s).toRight(s"unknown PassedAuthFactor: $s"))

case class AuthFactor(
    `type`: AuthFactorType,
    required: Boolean,
) derives JsonCodec, Schema, Equal

case class PrimaryAuthFlow(
    credentials: List[PrimaryCredential],
    inlinePassword: Boolean,
    factors: List[AuthFactor],
) derives JsonCodec, Schema, Equal

case class PasskeyAuthFlow(
    factors: List[AuthFactor],
) derives JsonCodec, Schema, Equal

case class AuthFlow(
    primary: PrimaryAuthFlow,
    passkey: Option[PasskeyAuthFlow],
    equivalents: Map[PassedAuthFactor, Set[PassedAuthFactor]],
) derives JsonCodec, Schema, Equal

object AuthFlow:
  val default: AuthFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.phone),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
    ),
    passkey = None,
    equivalents = Map.empty,
  )
