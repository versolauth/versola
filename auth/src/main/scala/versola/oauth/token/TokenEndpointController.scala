package versola.oauth.token

import versola.http.Controller
import versola.oauth
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, ClientSecret}
import versola.oauth.model.{AuthorizationCode, CodeVerifier, GrantType}
import versola.oauth.token.model.{ClientIdWithSecret, CodeExchangeRequest, TokenCredentials, TokenEndpointError, TokenErrorResponse, TokenRequest, TokenResponse}
import versola.security.Secret
import versola.util.FormDecoder
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

object TokenEndpointController extends Controller:
  type Env = Tracing & OAuthTokenService & OAuthClientService

  def routes: Routes[Env, Nothing] = Routes(
    tokenEndpoint,
  )

  val tokenEndpoint =
    Method.POST / "v1" / "token" -> handler { (request: Request) =>
      (for
        oauthTokenService <- ZIO.service[OAuthTokenService]
        tokenRequest <- parseRequest(request)
        credentials <- parseCredentials(request)
        response <- tokenRequest match
          case codeExchangeRequest: CodeExchangeRequest =>
            oauthTokenService.exchangeAuthorizationCode(codeExchangeRequest, credentials)
      yield Response.json(response.toJson))
        .catchAll {
          case error: TokenEndpointError =>
            ZIO.succeed:
              val errorResponse = TokenErrorResponse.from(error)
              Response
                .json(errorResponse.toJson)
                .status(Status.BadRequest)
                .addHeader(Header.CacheControl.NoStore)

          case ex: Throwable =>
            ZIO.succeed(Response.internalServerError)
        }
    }

  private def parseRequest(request: Request): IO[TokenEndpointError, TokenRequest] =
    for
      form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
      request <- form.get("grant_type").flatMap(_.stringValue) match
        case Some("authorization_code") =>
          ZIO.fromEither(codeExchangeRequestDecoder.decode(form)).orElseFail(TokenEndpointError.InvalidRequest)
        case _ =>
          ZIO.fail(TokenEndpointError.UnsupportedGrantType)
    yield request

  private def parseCredentials(
      request: Request,
  ): IO[TokenEndpointError, TokenCredentials] =
    request.header(Header.Authorization) match
      case Some(Header.Authorization.Basic(username, password)) =>
        val secret = password.stringValue
        if secret.isEmpty then
          ZIO.succeed(ClientIdWithSecret(ClientId(username), None))
        else
          ZIO.fromEither(Secret.fromBase64Url(secret))
            .mapBoth(
              _ => TokenEndpointError.InvalidClient,
              secret => ClientIdWithSecret(ClientId(username), Some(secret)),
            )
      case _ =>
        ZIO.fail(TokenEndpointError.InvalidClient)

  val codeExchangeRequestDecoder: FormDecoder[CodeExchangeRequest] = (form: Form) =>
    for
      code <- FormDecoder.single(form, "code", AuthorizationCode.fromBase64Url)
      redirectUri <- FormDecoder.single(form, "redirect_uri", URL.decode(_).left.map(_.getMessage))
      codeVerifier <- FormDecoder.single(form, "code_verifier", CodeVerifier.from)
    yield CodeExchangeRequest(code, redirectUri, codeVerifier)
