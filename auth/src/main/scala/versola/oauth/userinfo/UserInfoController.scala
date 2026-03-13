package versola.oauth.userinfo

import versola.oauth.model.AccessToken
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.util.http.Controller
import versola.util.CoreConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * UserInfo endpoint controller
 * OpenID Connect Core 1.0 Section 5.3
 *
 * Endpoints:
 *   - GET /v1/userinfo
 *   - POST /v1/userinfo
 *
 * Authentication: Bearer token (JWT access token) in Authorization header
 * Response: JSON object with user claims
 */
object UserInfoController extends Controller:
  type Env = Tracing & UserInfoService & CoreConfig

  def routes: Routes[Env, Nothing] = Routes(
    userInfoGetEndpoint,
    userInfoPostEndpoint,
  )

  val userInfoGetEndpoint = userInfoEndpoint(Method.GET)

  val userInfoPostEndpoint = userInfoEndpoint(Method.POST)

  private def userInfoEndpoint(method: Method) =
    method / "v1" / "userinfo" -> handler { (request: Request) =>
      (for
        userInfoService <- ZIO.service[UserInfoService]
        config <- ZIO.service[CoreConfig]
        tokenString <- extractBearerToken(request)
        jwt <- AccessToken.parseAndValidate(tokenString, config.jwt.jwkSet)
          .orElseFail(UserInfoError.InvalidToken)
        response <- userInfoService.getUserInfo(jwt)
      yield Response.json(response.toJsonAST.toJson))
        .catchAll:
          case UserInfoError.InvalidToken =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_token"),
                    errorDescription = Some("The access token is invalid or expired")
                  )
                )

          case UserInfoError.InsufficientScope =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("insufficient_scope"),
                    errorDescription = Some("The access token does not have sufficient scope")
                  )
                )

          case UserInfoError.Unauthorized =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_request"),
                    errorDescription = Some("The request is missing a required parameter or is otherwise malformed")
                  )
                )
    }

  private def extractBearerToken(request: Request): IO[UserInfoError, String] =
    ZIO.fromOption:
      request.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(token) => token.value.asString
    .orElseFail(UserInfoError.Unauthorized)

