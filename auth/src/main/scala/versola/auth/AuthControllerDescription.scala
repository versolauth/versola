package versola.auth

import versola.auth.model.{AttemptsLeft, AuthId, DeviceId, FinishPasskeyRequest, IssuedTokens, OtpCode, RefreshToken, StartPasskeyRequest, StartPasskeyResponse}
import versola.user.model.{Email, UserId}
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.http.{RoutePattern, Status}
import zio.schema.*
import zio.json.*
import zio.json.ast.Json
import com.nimbusds.jose.jwk.JWKSet

private[auth] object AuthControllerDescription:

  // Endpoint definitions
  val submitEmailEndpoint =
    Endpoint(RoutePattern.POST / "auth" / "start")
      .in[SubmitEmailRequest]
      .out[SubmitEmailResponse]
      .outError[Throwable](Status.InternalServerError)

  val submitOtpEndpoint =
    Endpoint(RoutePattern.POST / "auth" / "otp")
      .in[SubmitOtpRequest]
      .out[IssuedTokens]
      .outError[Throwable](Status.InternalServerError)
      .outError[SubmitOtpErrorResponse](Status.UnprocessableEntity)

  val refreshTokenEndpoint =
    Endpoint(RoutePattern.POST / "auth" / "refresh")
      .in[RefreshTokenRequest]
      .out[IssuedTokens]
      .outError[Throwable](Status.InternalServerError)
      .outError[Unit](Status.Unauthorized)

  val logoutEndpoint =
    Endpoint(RoutePattern.POST / "auth" / "logout")
      .out[Unit]
      .outError[Throwable](Status.InternalServerError)

  val jwksEndpoint =
    Endpoint(RoutePattern.GET / "auth" / "jwks").out[JWKSet]

  val startPasskeyEndpoint =
    Endpoint(RoutePattern.POST / "auth" / "passkey" / "start")
      .in[StartPasskeyRequest]
      .out[StartPasskeyResponse]
      .outError[Throwable](Status.InternalServerError)

  // Request/Response models
  case class SubmitEmailRequest(
      email: Email,
      deviceId: Option[DeviceId],
  ) derives Schema

  case class SubmitEmailResponse(
      authId: AuthId,
  ) derives Schema

  case class SubmitOtpRequest(
      code: OtpCode,
      authId: AuthId,
  ) derives Schema

  case class SubmitOtpErrorResponse(
      attemptsLeft: Int,
  ) derives Schema

  case class RefreshTokenRequest(
      refreshToken: RefreshToken,
      deviceId: DeviceId,
  ) derives Schema

  case class LogoutRequest(
      refreshToken: RefreshToken,
      deviceId: DeviceId,
  ) derives Schema

  // Schemas and HttpContentCodec instances (copied from Controller)
  def emptyJsonCodec[A]: HttpContentCodec[A] = HttpContentCodec.json
    .only[A](using Schema.fail("No schema"))

  given Schema[JWKSet] = Schema[String].transform(
    str => JWKSet.parse(str),
    _.toString,
  )

  given HttpContentCodec[JWKSet] = HttpContentCodec.json.only[JWKSet]
  given HttpContentCodec[Throwable] = emptyJsonCodec[Throwable]
  given HttpContentCodec[Unit] = emptyJsonCodec[Unit]
