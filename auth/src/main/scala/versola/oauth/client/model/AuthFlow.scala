package versola.oauth.client.model

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

enum PrimaryCredential derives JsonCodec, Schema, Equal:
  case email, phone, login

enum AuthFactorType derives JsonCodec, Schema, Equal:
  case otp, password

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
) derives JsonCodec, Schema, Equal

object AuthFlow:
  val default: AuthFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.phone),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
    ),
    passkey = None,
  )
