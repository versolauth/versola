package versola.auth

import versola.util.JWTDecoders.given
import com.nimbusds.jose.jwk.JWKSet
import versola.auth.model.{AttemptsLeft, AuthId, DeviceId, FinishPasskeyRequest, IssuedTokens, OtpCode, RefreshToken, StartPasskeyRequest, StartPasskeyResponse}
import versola.auth.AuthControllerDescription.*
import versola.http.{Controller, JWTAuthAspect}
import versola.user.model.{Email, UserId}
import zio.*
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.http.{RoutePattern, Routes, Status}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthController extends Controller:
  type Env = JWKSet & AuthService & Tracing

  def routes: Routes[Env, Nothing] = Routes(
    submitEmailEndpoint.implement: request =>
      ZIO.serviceWithZIO[AuthService](_.sendEmail(request.email, request.deviceId)
        .map(SubmitEmailResponse(_)))
        .tapError(ex => Controller.exceptions.set(Some(ex))),
    submitOtpEndpoint.implement: request =>
      ZIO.serviceWithZIO[AuthService](_
        .verifyEmail(request.code, request.authId)
        .tapSomeError { case ex: Throwable => Controller.exceptions.set(Some(ex)) }
        .mapError {
          case attemptsLeft: AttemptsLeft => Left(SubmitOtpErrorResponse(attemptsLeft))
          case ex: Throwable => Right(ex)
        }),
    jwksEndpoint.implementAsZIO(ZIO.service[JWKSet]),
    refreshTokenEndpoint.implement: request =>
      ZIO.serviceWithZIO[AuthService] {
        _.refreshTokens(request.refreshToken, request.deviceId)
          .tapSomeError { case ex: Throwable => Controller.exceptions.set(Some(ex)) }
          .mapError {
            case ex: Unit => Left(ex)
            case ex: Throwable => Right(ex)
          }
      },
    startPasskeyEndpoint.implement: request =>
      ZIO.serviceWithZIO[AuthService](_.startPasskey(request.username, request.displayName))
        .tapError(ex => Controller.exceptions.set(Some(ex))),
  ) ++ logoutEndpoint.implementAsZIO {
    for
      (userId, deviceId) <- ZIO.service[(userId: UserId, deviceId: DeviceId)]
      authService <- ZIO.service[AuthService]
      _ <- authService.logout(userId, deviceId)
        .tapSomeError { case ex: Throwable => Controller.exceptions.set(Some(ex)) }
    yield ()
  }.toRoutes @@ JWTAuthAspect.authorize[(userId: UserId, deviceId: DeviceId)]

  val layer: ZLayer[Env, Nothing, Routes[Any, Nothing]] =
    ZLayer:
      for
        jwks <- ZIO.service[JWKSet]
        authService <- ZIO.service[AuthService]
        tracing <- ZIO.service[Tracing]
      yield routes.provideEnvironment(
        ZEnvironment(jwks) ++ ZEnvironment(authService) ++ ZEnvironment(tracing),
      )
