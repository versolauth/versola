package versola.oauth.revoke

import versola.oauth.client.model.ClientIdWithSecret
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.{RevocationError, RevocationErrorResponse}
import versola.util.http.{Controller, Observability, extractCredentials}
import versola.util.{Base64, CoreConfig, FormDecoder, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth 2.0 Token Revocation Endpoint
 * RFC 7009: https://datatracker.ietf.org/doc/html/rfc7009
 */
object RevocationController extends Controller:
  type Env = Tracing & RevocationService & JwksService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    revokeEndpoint,
  )

  val revokeEndpoint =
    Method.POST / "revoke" -> handler { (request: Request) =>
      (for
        revocationService <- ZIO.service[RevocationService]
        config <- ZIO.service[CoreConfig]
        publicKeys <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys)
        form <- request.body.asURLEncodedForm.orElseFail(RevocationError.InvalidClient)
        credentials <- request.extractCredentials(form).orElseFail(RevocationError.InvalidClient)
        _ <- credentials match
          case ClientIdWithSecret(clientId, _) => Observability.setClientId(clientId)

        token <- tokenDecoder.decode(form)
          .orElseFail(RevocationError.InvalidClient)

        _ <- token match
          case Right(accessToken) =>
            Observability.setRouteLabel("token_type", "access") *>
              JWT.deserialize[AccessTokenPayload](accessToken, publicKeys, JWT.Type.AccessToken)
                .tap(payload =>
                  Observability.setToken(payload.id.encoded) *>
                    ZIO.foreachDiscard(payload.userId)(uid => Observability.setUserId(uid.toString)),
                )
                .flatMap(revocationService.revokeAccessToken(_, credentials))
                .catchSome {
                  case error: RevocationError =>
                    val response = RevocationErrorResponse.fromError(error)
                    Observability.setError(response.error, response.errorDescription)
                  case _: JWT.Error =>
                    Observability.setError("invalid_token", Some("The presented access token could not be verified"))
                }

          case Left(refreshToken) =>
            Observability.setRouteLabel("token_type", "refresh") *>
              Observability.setRefreshToken(Base64.urlEncode(refreshToken)) *>
              revocationService.revokeRefreshToken(refreshToken, credentials)
                .catchSome {
                  case error: RevocationError =>
                    val response = RevocationErrorResponse.fromError(error)
                    Observability.setError(response.error, response.errorDescription)
                }
      yield Response.ok)
        .catchAll {
          case error: RevocationError =>
            val response = RevocationErrorResponse.fromError(error)
            Observability.setError(response.error, response.errorDescription).as:
              Response
                .json(response.toJson)
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

