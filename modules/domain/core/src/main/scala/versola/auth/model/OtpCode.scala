package versola.auth.model

import zio.schema.Schema

type OtpCode = OtpCode.Type

object OtpCode:
  given Schema[OtpCode] = Schema.primitive[String]
    .transformOrFail(
      string => Either.cond(string.length == 6 && string.forall(_.isDigit), OtpCode(string), "Invalid OTP code"),
      Right(_),
    )

  opaque type Type <: String = String
  inline def apply(code: String): OtpCode = code
