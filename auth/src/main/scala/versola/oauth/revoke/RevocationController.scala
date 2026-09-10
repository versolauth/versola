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
        form <- request.body.asURLEncodedForm.orElseFail(RevocationError.InvalidRequest)
        credentials <- request.extractCredentials(form).orElseFail(RevocationError.InvalidClient)
        _ <- credentials match
          case ClientIdWithSecret(clientId, _) => Observability.setClientId(clientId)

        token <- FormDecoder.single(form, "token", (s: String) => Right(s))
          .orElseFail(RevocationError.InvalidRequest)

        _ <- classify(token) match
          // RFC 7009 §2.2: a value this server could never have issued is not reported as an
          // error - the caller's goal, that the token not be usable, already holds. Only client
          // authentication and token ownership are refusable (§2.1), and those failures come
          // out of RevocationService as a RevocationError below.
          case None =>
            Observability.setError("invalid_token", Some("The presented token is not of a recognized form"))

          case Some(Right(accessToken)) =>
            Observability.setRouteLabel("token_type", "access") *>
              JWT.deserialize[AccessTokenPayload](accessToken, publicKeys, JWT.Type.AccessToken)
                .tap(payload =>
                  Observability.setToken(payload.id.encoded) *>
                    ZIO.foreachDiscard(payload.userId)(uid => Observability.setUserId(uid.toString)),
                )
                .flatMap(revocationService.revokeAccessToken(_, credentials))
                .catchSome {
                  case _: JWT.Error =>
                    Observability.setError("invalid_token", Some("The presented access token could not be verified"))
                }

          case Some(Left(refreshToken)) =>
            Observability.setRouteLabel("token_type", "refresh") *>
              Observability.setRefreshToken(Base64.urlEncode(refreshToken)) *>
              revocationService.revokeRefreshToken(refreshToken, credentials)
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

  /** Classifies the presented token by its own shape: a JWT is an access token, any other
    * base64url value is taken for a refresh token, and a value that is neither is one no client
    * could hold. `token_type_hint` is deliberately not consulted - RFC 7009 §2.1 makes it a
    * lookup optimization, not a declaration the server may trust. */
  private def classify(token: String): Option[Either[RefreshToken, String]] =
    if token.isJWT then Some(Right(token))
    else RefreshToken.fromBase64Url(token).toOption.map(Left(_))

