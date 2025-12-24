package versola.oauth.introspect

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jwt.SignedJWT
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.ClientId
import versola.oauth.introspect.model.IntrospectionError
import versola.oauth.token.model.{TokenEndpointError, TokenErrorResponse}
import versola.util.{FormDecoder, Secret}
import versola.util.http.{ClientCredentials, ClientIdWithSecret, Controller}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth 2.0 Token Introspection Endpoint
 * RFC 7662: https://datatracker.ietf.org/doc/html/rfc7662
 */
object IntrospectionController extends Controller:
  type Env = Tracing & IntrospectionService

  def routes: Routes[Env, Nothing] = Routes(
    introspectEndpoint,
  )

  val introspectEndpoint =
    Method.POST / "v1" / "introspect" -> handler { (request: Request) =>
      (for
        introspectionService <- ZIO.service[IntrospectionService]
        credentials <- request.extractCredentials.orElseFail(TokenEndpointError.InvalidClient)
        introspectionRequest <- request.formAs[IntrospectionRequest]
        response <- introspectionRequest.token match
          case AccessTokenOption(accessToken) =>
            introspectionService.introspectOpaqueAccessToken(accessToken, credentials)

          case JWTAccessTokenOption(accessToken) =>
            introspectionService.introspectJWTAccessToken(accessToken, credentials)

          case RefreshTokenOption(refreshToken) =>
            introspectionService.introspectRefreshToken(refreshToken, credentials)

      yield Response.json(response.toJson)
        .addHeader(Header.CacheControl.NoStore)
        .addHeader(Header.Pragma.NoCache))
        .catchAll {
          case _: IntrospectionError =>
            ZIO.succeed:
              Response
                .json(TokenErrorResponse.from(TokenEndpointError.InvalidClient).toJson)
                .status(Status.Unauthorized)
                .addHeader(Header.CacheControl.NoStore)

          case error: TokenEndpointError =>
            ZIO.succeed:
              val errorResponse = TokenErrorResponse.from(error)
              Response
                .json(errorResponse.toJson)
                .status(error.status)
                .addHeader(Header.CacheControl.NoStore)

          case ex: Throwable =>
            ZIO.logErrorCause("Introspection error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  case class IntrospectionRequest(
      token: TokenOption,
  )

  sealed trait TokenOption

  case class RefreshTokenOption(refreshToken: RefreshToken) extends TokenOption
  case class AccessTokenOption(accessToken: AccessToken) extends TokenOption
  case class JWTAccessTokenOption(accessToken: SignedJWT) extends TokenOption

  given FormDecoder[IntrospectionRequest] = form =>
    val parse = (s: String) =>
      if s.startsWith("t.") then
        AccessToken.fromBase64Url(s.drop(2)).map(AccessTokenOption(_))
      else if s.startsWith("r.") then
        RefreshToken.fromBase64Url(s.drop(2)).map(RefreshTokenOption(_))
      else
        util.Try(SignedJWT.parse(s))
          .map(JWTAccessTokenOption(_))
          .toEither.left.map(_.getMessage)

    for
      token <- FormDecoder.single(form, "token", parse)
    yield IntrospectionRequest(token)
