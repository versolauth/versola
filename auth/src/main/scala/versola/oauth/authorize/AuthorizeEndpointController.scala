package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.conversation.ConversationRenderService
import versola.oauth.conversation.model.{ConversationRecord, ConversationStep}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, ConversationCookie}
import versola.util.{Base64Url, CoreConfig}
import versola.util.http.Controller
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
            ZIO.succeed(Response.badRequest(Error.BadRequest.description))

          case error: Error.RedirectError =>
            ZIO.succeed(Response.seeOther(error.redirectUriWithErrorParams))
        }
    }

  private def authorizeAndRedirect(request: AuthorizeRequest) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      configService <- ZIO.service[OAuthConfigurationService]
      config <- ZIO.service[CoreConfig]
      authConversationTtl <- configService.getAuthConversationTtl(request.clientId)
      response <- authService.authorize(request).map:
        case AuthorizeResponse.Authorized(code, idToken) =>
          Response.seeOther(
            AuthorizeRedirect.responseUrl(request.redirectUri, Base64Url.encode(code), request.state, idToken),
          )

        case AuthorizeResponse.Initialize(authId) =>
          Response.seeOther(URL.empty / "challenge")
            .addCookie(
              ConversationCookie.responseCookie(
                ConversationCookie(authId, request.clientId),
                authConversationTtl,
                config.security.conversationCookieSecret,
              ),
            )
    yield response
