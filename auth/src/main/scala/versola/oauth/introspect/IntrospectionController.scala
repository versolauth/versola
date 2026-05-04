package versola.oauth.introspect

import versola.oauth.introspect.model.{IntrospectionError, IntrospectionErrorResponse, IntrospectionResponse}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.util.{Base64, Base64Url, CoreConfig, FormDecoder, JWT}
import versola.util.http.{Controller, extractCredentials}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth 2.0 Token Introspection Endpoint
 * RFC 7662: https://datatracker.ietf.org/doc/html/rfc7662
 */
object IntrospectionController extends Controller:
  type Env = Tracing & IntrospectionService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    introspectEndpoint,
  )

  val introspectEndpoint =
    Method.POST / "v1" / "introspect" -> handler { (request: Request) =>
      (for
        introspectionService <- ZIO.service[IntrospectionService]
        config <- ZIO.service[CoreConfig]
        credentials <- request.extractCredentials.orElseFail(IntrospectionError.InvalidClient)
        tokenEither <- request.formAs[Either[RefreshToken, String]].orElseFail(IntrospectionError.InvalidRequest)
        response <- tokenEither match
          case Right(token) =>
            JWT.deserialize[AccessTokenPayload](token, config.jwt.publicKeys)
              .flatMap(introspectionService.introspectAccessToken(_, credentials))
              .catchSome { case _: IntrospectionError => ZIO.succeed(IntrospectionResponse.Inactive) }

          case Left(refreshToken) =>
            introspectionService.introspectRefreshToken(refreshToken, credentials)

      yield Response.json(response.toJson)
        .addHeader(Header.CacheControl.NoStore)
        .addHeader(Header.Pragma.NoCache))
        .catchAll {
          case _: JWT.Error =>
            ZIO.succeed(Response.json(IntrospectionResponse.Inactive.toJson))

          case error: IntrospectionError =>
            ZIO.succeed:
              Response
                .json(IntrospectionErrorResponse.from(error).toJson)
                .status(error.status)
                .addHeader(Header.CacheControl.NoStore)

          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  given FormDecoder[Either[RefreshToken, String]] = form =>
    val parse = (s: String) =>
      if s.isJWT then
        Right(Right(s))
      else
        RefreshToken.fromBase64Url(s).map(Left(_))

    FormDecoder.single(form, "token", parse)
