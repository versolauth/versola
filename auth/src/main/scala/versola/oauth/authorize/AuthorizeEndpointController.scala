package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.AuthMetrics
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.model.ConversationCookie
import versola.util.{Base64Url, CoreConfig}
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser & AuthorizeEndpointService & OAuthConfigurationService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    getAuthorizeRoute,
    postAuthorizeRoute,
  )

  val getAuthorizeRoute = authorize(Method.GET)
  val postAuthorizeRoute = authorize(Method.POST)

  def authorize(method: Method): Route[Env, Throwable] =
    method / "authorize" -> handler { (request: Request) =>
      val result =
        for
          parser <- ZIO.service[AuthorizeRequestParser]
          parsedRequest <- parser.parse(request)
          response <- authorizeAndRedirect(parsedRequest)
        yield response
      result
        .catchSome {
          case Error.BadRequest =>
            AuthMetrics.authorizeError("invalid_request") *>
              (Observability.setError("invalid_request", Some(Error.BadRequest.description))
                .as(Response.badRequest(Error.BadRequest.description)))

          case error: Error.RedirectError =>
            for
              config <- ZIO.service[CoreConfig]
              _ <- AuthMetrics.authorizeError(error.error.toString)
              _ <- Observability.setError(error.error, Some(error.errorDescription))
            yield Response.seeOther(error.redirectUriWithErrorParams(config.jwt.issuer))
        }
    }

  private def authorizeAndRedirect(request: AuthorizeRequest) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      configService <- ZIO.service[OAuthConfigurationService]
      config <- ZIO.service[CoreConfig]
      authConversationTtl <- configService.getAuthConversationTtl(request.clientId)
      response <- authService.authorize(request).tap(AuthMetrics.authorizeOutcome).map:
        case AuthorizeResponse.Authorized(code, idToken) =>
          Response.seeOther(
            AuthorizeRedirect.responseUrl(request.redirectUri, Base64Url.encode(code), request.state, idToken, config.jwt.issuer),
          )

        case AuthorizeResponse.Initialize(authId) =>
          Response.seeOther(URL.root / "challenge")
            .addCookie(
              ConversationCookie.responseCookie(
                ConversationCookie(
                  authId,
                  request.clientId,
                  redirectUri = request.redirectUri.encode,
                  state = request.state,
                  useFragment = Some(request.isHybrid),
                ),
                authConversationTtl,
                config.security.conversationCookieSecret,
              ),
            )
    yield response
