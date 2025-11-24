package versola.oauth.authorize

import versola.http.Controller
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.{ConversationRecord, ConversationStep}
import versola.oauth.forms.ConversationRenderService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, ConversationCookie}
import versola.util.CoreConfig
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser & AuthorizeEndpointService & CoreConfig

  def routes: Routes[Env, Nothing] = Routes(
    getAuthorizeRoute,
    postAuthorizeRoute,
  )

  val getAuthorizeRoute = authorize(Method.GET)
  val postAuthorizeRoute = authorize(Method.POST)

  def authorize(method: Method): Route[Env, Nothing] =
    method / "v1" / "authorize" -> handler { (request: Request) =>
      val result =
        for
          parser <- ZIO.service[AuthorizeRequestParser]
          parsedRequest <- parser.parse(request)
          response <- authorizeAndRedirect(parsedRequest)
        yield response
      result
        .catchAll {
          case Error.BadRequest =>
            ZIO.succeed(Response.badRequest(Error.BadRequest.description))

          case error: Error.RedirectError =>
            ZIO.succeed(Response.seeOther(error.redirectUriWithErrorParams))

          case ex: Throwable =>
            ZIO.logErrorCause("Unknown exception", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  private def authorizeAndRedirect(request: AuthorizeRequest) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      conversationConfig <- ZIO.service[CoreConfig]
      response <- authService.authorize(request).map:
        case AuthorizeResponse.Authorized(code) =>
          Response.seeOther(request.buildResponseUri(code))

        case AuthorizeResponse.Initialize(authId) =>
          Response.seeOther(URL.empty / "challenge")
            .addCookie(ConversationCookie(authId, conversationConfig.security.authConversation.ttl))
    yield response