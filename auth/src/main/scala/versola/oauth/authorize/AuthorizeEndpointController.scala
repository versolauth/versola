package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeErrorResponse, AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.AuthMetrics
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.model.ConversationCookie
import versola.util.{Base64Url, CoreConfig}
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.prelude.NonEmptySet
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser & AuthorizeEndpointService & AuthorizationResponseService &
    OAuthConfigurationService & CoreConfig

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

          case Error.InvalidRequestObject =>
            // RFC 9101 §6.2: an object that fails verification must be reported as
            // `invalid_request_object`, not as an undifferentiated 400 -- that is the only
            // thing that lets a client tell this apart from Error.BadRequest above.
            AuthMetrics.authorizeError(Error.InvalidRequestObject.error) *>
              (Observability.setError(Error.InvalidRequestObject.error, Some(Error.InvalidRequestObject.description))
                .as(Response.json(
                  AuthorizeErrorResponse(Error.InvalidRequestObject.error, Error.InvalidRequestObject.description).toJson,
                ).status(Status.BadRequest)))

          case error: Error.RedirectError =>
            for
              config <- ZIO.service[CoreConfig]
              responseService <- ZIO.service[AuthorizationResponseService]
              _ <- AuthMetrics.authorizeError(error.error.toString)
              _ <- Observability.setError(error.error, Some(error.errorDescription))
              redirect <- responseService.redirect(
                error.clientId,
                error.uri,
                error.responseMode,
                error.errorParams(config.jwt.issuer),
              )
            yield Response.seeOther(redirect)
        }
    }

  private def authorizeAndRedirect(request: AuthorizeRequest) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      configService <- ZIO.service[OAuthConfigurationService]
      responseService <- ZIO.service[AuthorizationResponseService]
      config <- ZIO.service[CoreConfig]
      authConversationTtl <- configService.getAuthConversationTtl(request.clientId)
      response <- authService.authorize(request).tap(AuthMetrics.authorizeOutcome).flatMap:
        case AuthorizeResponse.Authorized(code, idToken) =>
          responseService.redirect(
            request.clientId,
            request.redirectUri,
            request.responseMode,
            AuthorizeRedirect.successParams(Base64Url.encode(code), request.state, idToken, config.jwt.issuer),
          ).map(Response.seeOther)

        case AuthorizeResponse.Initialize(authId) =>
          ZIO.succeed(
            Response.seeOther(URL.root / "challenge")
              .addCookie(
                ConversationCookie.responseCookie(
                  ConversationCookie(
                    authId,
                    request.clientId,
                    redirectUri = request.redirectUri.encode,
                    state = request.state,
                    responseMode = Some(request.responseMode),
                  ),
                  authConversationTtl,
                  config.security.conversationCookieSecret,
                ),
              ),
          )
    yield response
