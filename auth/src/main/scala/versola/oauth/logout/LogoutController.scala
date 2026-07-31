package versola.oauth.logout

import versola.oauth.conversation.ConversationRenderService
import versola.oauth.jwks.JwksService
import versola.oauth.model.{SessionCookie, State}
import versola.oauth.session.model.{PublicSessionId, SessionId}
import versola.util.{CoreConfig, JWT}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

object LogoutController extends Controller:
  type Env = Tracing & LogoutService & ConversationRenderService & CoreConfig & JwksService

  private case class LogoutHintClaims(
      sid: PublicSessionId,
      sub: String,
  ) derives JsonDecoder

  def routes: Routes[Env, Throwable] = Routes(
    getLogoutRoute,
    postLogoutRoute,
  )

  val getLogoutRoute = logout(Method.GET)
  val postLogoutRoute = logout(Method.POST)

  def logout(method: Method): Route[Env, Throwable] =
    method / "logout" -> handler { (request: Request) =>
      for
        postLogoutRedirectUri <- request.queryZIO[Option[URL]]("post_logout_redirect_uri")
        state <- request.queryZIO[Option[State]]("state")
        idTokenHint <- request.queryZIO[Option[String]]("id_token_hint")
        config <- ZIO.service[CoreConfig]
        sessionId = request.cookie(SessionCookie.name)
          .flatMap(cookie => SessionCookie.parse(cookie.content, config.security.sessionCookieSecret).toOption)
        renderService <- ZIO.service[ConversationRenderService]
        // The browser cookie carries the session credential; the id_token_hint only the public
        // `sid`. Either identifies the same session, the cookie wins when both are present.
        identifier <- resolveIdentifier(sessionId, idTokenHint)
        // Without either identifier there is no session to terminate and no client to notify;
        // still respond 200 OK per OIDC Front-Channel Logout, just without any logout iframes.
        response <- identifier match
          case None =>
            renderService.renderLogout(List.empty, postLogoutRedirectUri, state)
          case Some(id) =>
            for
              logoutService <- ZIO.service[LogoutService]
              result <- logoutService.logout(id, postLogoutRedirectUri, state)
              rendered <- renderService.renderLogout(result.logoutUris, result.postLogoutRedirectUri, result.state)
            yield rendered.addCookie(SessionCookie.expired)
      yield response
    }

  private def resolveIdentifier(
      sessionId: Option[SessionId],
      idTokenHint: Option[String],
  ): RIO[JwksService, Option[Either[PublicSessionId, SessionId]]] =
    sessionId match
      case Some(id) => ZIO.some(Right(id))
      case None =>
        idTokenHint match
          case None        => ZIO.none
          case Some(token) => sessionIdFromIdToken(token).map(_.map(Left(_)))

  private def sessionIdFromIdToken(token: String): RIO[JwksService, Option[PublicSessionId]] =
    ZIO.serviceWithZIO[JwksService](_.getPublicKeys).flatMap: keys =>
      JWT.deserialize[LogoutHintClaims](token, keys, JWT.Type.JWT, validateExpiry = false)
        .option
        .map(_.map(_.sid))
