package versola.oauth.userinfo

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.model.AccessToken
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.util.http.Controller
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.util.Date
import scala.jdk.CollectionConverters.*

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
        token <- JWT.deserialize[AccessToken](tokenString, config.jwt.publicKeys)
          .orElseFail(UserInfoError.InvalidToken)

        userId <- ZIO.fromOption(token.userId).orElseFail(UserInfoError.InvalidToken)

        userInfo <- userInfoService.getUserInfo(userId, token.scope, token.requestedClaims, token.uiLocales)

        jwtNeeded = request.header(Header.Accept)
          .exists(_.mimeTypes.exists(_.mediaType == MediaType.application.jwt))

        response <-
          if !jwtNeeded then
            ZIO.succeed(Response.json(userInfo.toJsonAST.toJson))
          else
            JWT.serialize(
              claims = JWT.Claims(
                issuer = config.jwt.issuer,
                subject = userId.toString,
                audience = List(token.clientId),
                custom = userInfo.toJsonAST,
              ),
              ttl = 5.minutes,
              signature = JWT.Signature(
                publicKeys = config.jwt.publicKeys,
                privateKey = config.jwt.privateKey,
              ),
            ).map { signedJwt =>
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.application.jwt)),
                body = Body.fromString(signedJwt),
              )
            }
      yield response)
        .catchAll:
          case UserInfoError.InvalidToken =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_token"),
                    errorDescription = Some("The access token is invalid or expired"),
                  ),
                )

          case UserInfoError.InsufficientScope =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("insufficient_scope"),
                    errorDescription = Some("The access token does not have sufficient scope"),
                  ),
                )

          case UserInfoError.Unauthorized =>
            ZIO.succeed:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_request"),
                    errorDescription = Some("The request is missing a required parameter or is otherwise malformed"),
                  ),
                )

          case _: Throwable =>
            ZIO.succeed:
              Response
                .status(Status.InternalServerError)
    }

  private def extractBearerToken(request: Request): IO[UserInfoError, String] =
    ZIO.fromOption:
      request.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(token) => token.value.asString
    .orElseFail(UserInfoError.Unauthorized)
