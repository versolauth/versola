package versola.oauth.revoke

import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.{RevocationError, RevocationErrorResponse}
import versola.util.http.{Controller, extractCredentials}
import versola.util.{CoreConfig, FormDecoder, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth 2.0 Token Revocation Endpoint
 * RFC 7009: https://datatracker.ietf.org/doc/html/rfc7009
 */
object RevocationController extends Controller:
  type Env = Tracing & RevocationService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    revokeEndpoint,
  )

  val revokeEndpoint =
    Method.POST / "v1" / "revoke" -> handler { (request: Request) =>
      (for
        revocationService <- ZIO.service[RevocationService]
        config <- ZIO.service[CoreConfig]
        credentials <- request.extractCredentials.orElseFail(RevocationError.InvalidClient)
        form <- request.body.asURLEncodedForm.orElseFail(RevocationError.InvalidClient)

        token <- request.formAs[Either[RefreshToken, String]]
          .orElseFail(RevocationError.InvalidClient)

        _ <- token match
          case Right(accessToken) =>
            JWT.deserialize[AccessTokenPayload](accessToken, config.jwt.publicKeys)
              .flatMap(revocationService.revokeAccessToken(_, credentials))
              .catchSome {
                case _: RevocationError => ZIO.unit
                case _: JWT.Error => ZIO.unit
              }

          case Left(refreshToken) =>
            revocationService.revokeRefreshToken(refreshToken, credentials)
              .catchSome { case _: RevocationError => ZIO.unit }
      yield Response.ok)
        .catchAll {
          case error: RevocationError =>
            ZIO.succeed:
              Response
                .json(RevocationErrorResponse.fromError(error).toJson)
                .status(error.status)
          case _: JWT.Error =>
            ZIO.succeed(Response.ok)

          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  private given tokenDecoder: FormDecoder[Either[RefreshToken, String]] = form =>
    val parse = (s: String) =>
      if s.isJWT then
        Right(Right(s))
      else
        RefreshToken.fromBase64Url(s).map(Left(_))
    FormDecoder.single(form, "token", parse)

