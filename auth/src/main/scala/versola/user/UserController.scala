package versola.user

import com.nimbusds.jose.jwk.JWKSet
import versola.http.{Controller, JWTAuthAspect}
import versola.user.model.{PatchUserErrorResponse, PatchUserRequest, UserId, UserNotFound, UserResponse}
import versola.util.JWTDecoders.given
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.http.{RoutePattern, Routes, Status}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Tag, ZIO}

object UserController extends Controller:
  type Env = JWKSet & UserService & Tracing

  val updateUserEndpoint =
    Endpoint(RoutePattern.PATCH / "api" / "v1" / "user")
      .in[PatchUserRequest]
      .out[PatchUserErrorResponse](Status.Ok)
      .outError[Throwable](Status.InternalServerError)

  val getUserEndpoint =
    Endpoint(RoutePattern.GET / "api" / "v1" / "user")
      .out[UserResponse](Status.Ok)
      .outError[Throwable](Status.InternalServerError)
      .outError[UserNotFound](Status.NoContent)

  def routes: Routes[Env, Nothing] = Routes(
    updateUserEndpoint.implement: request =>
      for
        userService <- ZIO.service[UserService]
        userId <- ZIO.service[UserId]
        result <- userService.updateProfile(userId, request)
          .tapSomeError { case ex: Throwable => Controller.exceptions.set(Some(ex)) }
      yield result,
    getUserEndpoint.implement: _ =>
      for
        userService <- ZIO.service[UserService]
        userId <- ZIO.service[UserId]
        result <- userService.getProfile(userId)
          .tapSomeError { case ex: Throwable => Controller.exceptions.set(Some(ex)) }
          .mapError:
            case ex: Throwable => Right(ex)
            case ex: UserNotFound => Left(ex)
      yield result,
  ) @@ JWTAuthAspect.authorize[UserId]

  given HttpContentCodec[Throwable] = emptyJsonCodec
  given HttpContentCodec[UserNotFound] = emptyJsonCodec
