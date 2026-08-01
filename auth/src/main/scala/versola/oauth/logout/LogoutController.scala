package versola.oauth.logout

import versola.oauth.conversation.ConversationRenderService
import versola.oauth.jwks.JwksService
import versola.oauth.model.{SessionCookie, State}
import versola.oauth.session.model.{PublicSessionId, SessionId}
import versola.oauth.session.SessionService
import versola.util.{Base64, CoreConfig, FormDecoder, JWT}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object LogoutController extends Controller:
  type Env = Tracing & LogoutService & ConversationRenderService & CoreConfig & JwksService & SessionService

  private case class LogoutHintClaims(
      sid: PublicSessionId,
      sub: String,
      iss: String,
      aud: List[String],
  ) derives JsonDecoder

  def routes: Routes[Env, Throwable] = Routes(
    getLogoutRoute,
    postLogoutRoute,
  )

  // RP-initiated logout: renders the confirmation page for a plain session cookie, or logs out
  // immediately when `id_token_hint` identifies the same session (no confirmation needed since the
  // hint proves the RP already knows who is being logged out).
  val getLogoutRoute: Route[Env, Throwable] =
    Method.GET / "logout" -> handler { (request: Request) =>
      for
        postLogoutRedirectUri <- request.queryZIO[Option[URL]]("post_logout_redirect_uri")
        state <- request.queryZIO[Option[State]]("state")
        idTokenHint <- request.queryZIO[Option[String]]("id_token_hint")
        config <- ZIO.service[CoreConfig]
        sessionId = sessionIdFromCookie(request, config)
        renderService <- ZIO.service[ConversationRenderService]
        hint <- resolveHint(idTokenHint)
        // Without either identifier there is no session to terminate and no client to validate
        // the redirect against, so it is dropped rather than passed through unvalidated; still
        // respond 200 OK per OIDC Front-Channel Logout, just without any logout iframes.
        response <- (sessionId, hint) match
          case (Some(rawId), None) =>
            ZIO.serviceWithZIO[SessionService](_.find(rawId)).flatMap {
              case Some(info) =>
                val redirect = postLogoutRedirectUri.map(_.encode)
                renderService.renderLogoutConfirm(info, csrfToken(rawId, redirect, state, config), redirect, state, None)
              case None => renderService.renderLogout(List.empty, None, state)
            }
          case (Some(rawId), Some(hintId)) =>
            ZIO.serviceWithZIO[SessionService](_.find(rawId)).flatMap {
              case Some(info) if info.record.publicId == hintId => performLogout(Right(rawId), postLogoutRedirectUri, state, renderService)
              case _ => renderService.renderLogout(List.empty, None, state)
            }
          case (None, Some(hintId)) => performLogout(Left(hintId), postLogoutRedirectUri, state, renderService)
          case (None, None) => renderService.renderLogout(List.empty, None, state)
      yield response
    }

  // Submission of the confirmation page: the logout parameters travel in the form body, signed by
  // the token minted on GET, so the query string is never consulted here. `id_token_hint` is still
  // accepted via the query string for RPs that POST their logout request directly.
  val postLogoutRoute: Route[Env, Throwable] =
    Method.POST / "logout" -> handler { (request: Request) =>
      for
        postLogoutRedirectUri <- request.queryZIO[Option[URL]]("post_logout_redirect_uri")
        state <- request.queryZIO[Option[State]]("state")
        idTokenHint <- request.queryZIO[Option[String]]("id_token_hint")
        config <- ZIO.service[CoreConfig]
        sessionId = sessionIdFromCookie(request, config)
        renderService <- ZIO.service[ConversationRenderService]
        hint <- resolveHint(idTokenHint)
        response <- (sessionId, hint) match
          case (Some(rawId), None) =>
            request.formAs[LogoutConfirmSubmission].foldZIO(
              _ => renderService.renderLogout(List.empty, None, None),
              submission =>
                val expected = csrfToken(rawId, submission.postLogoutRedirectUri, submission.state, config)
                if matches(submission.csrfToken, expected) then
                  performLogout(Right(rawId), submission.postLogoutRedirectUri.flatMap(URL.decode(_).toOption), submission.state, renderService)
                else
                  renderService.renderLogout(List.empty, None, None),
            )
          case (Some(rawId), Some(hintId)) =>
            ZIO.serviceWithZIO[SessionService](_.find(rawId)).flatMap {
              case Some(info) if info.record.publicId == hintId => performLogout(Right(rawId), postLogoutRedirectUri, state, renderService)
              case _ => renderService.renderLogout(List.empty, None, state)
            }
          case (None, Some(hintId)) => performLogout(Left(hintId), postLogoutRedirectUri, state, renderService)
          case (None, None) => renderService.renderLogout(List.empty, None, state)
      yield response
    }

  private def sessionIdFromCookie(request: Request, config: CoreConfig): Option[SessionId] =
    request.cookie(SessionCookie.name)
      .flatMap(cookie => SessionCookie.parse(cookie.content, config.security.sessionCookieSecret).toOption)

  private def performLogout(identifier: Either[PublicSessionId, SessionId], redirect: Option[URL], state: Option[State], render: ConversationRenderService): RIO[LogoutService, Response] =
    for
      result <- ZIO.serviceWithZIO[LogoutService](_.logout(identifier, redirect, state))
      rendered <- render.renderLogout(result.logoutUris, result.postLogoutRedirectUri, result.state)
    yield rendered.addCookie(SessionCookie.expired)

  private def resolveHint(token: Option[String]): RIO[JwksService & CoreConfig, Option[PublicSessionId]] = token match
    case None => ZIO.none
    case Some(value) =>
      for
        config <- ZIO.service[CoreConfig]
        claims <- sessionIdFromIdToken(value)
      yield claims.filter(_.iss == config.jwt.issuer).filter(_.aud.nonEmpty).map(_.sid)

  private def sessionIdFromIdToken(token: String): RIO[JwksService, Option[LogoutHintClaims]] =
    ZIO.serviceWithZIO[JwksService](_.getPublicKeys).flatMap: keys =>
      JWT.deserialize[LogoutHintClaims](token, keys, JWT.Type.JWT).option

  private case class LogoutConfirmSubmission(
      csrfToken: String,
      postLogoutRedirectUri: Option[String],
      state: Option[State],
  )

  private given FormDecoder[LogoutConfirmSubmission] = (form: Form) =>
    for
      csrfToken <- FormDecoder.single(form, "csrf_token", Right(_))
      redirect  <- FormDecoder.optional(form, "post_logout_redirect_uri", Right(_))
      state     <- FormDecoder.optional(form, "state", s => Right(State(s)))
    yield LogoutConfirmSubmission(csrfToken, redirect, state)

  /** Binds the confirmation token to the logout parameters, so the form can resubmit them without
    * the server keeping any per-request state: altering either value invalidates the token. Parts
    * are length-prefixed so a boundary cannot be shifted to forge the same MAC input. */
  private def csrfToken(id: SessionId, redirectUri: Option[String], state: Option[String], config: CoreConfig): String =
    val canonical = List("logout-confirm", Base64.urlEncode(id), redirectUri.getOrElse(""), state.getOrElse(""))
      .map(part => s"${part.length}:$part")
      .mkString
    val mac = Array.ofDim[Byte](32)
    org.apache.commons.codec.digest.Blake3
      .initKeyedHash(config.security.sessionCookieSecret)
      .update(canonical.getBytes(StandardCharsets.UTF_8))
      .doFinalize(mac)
    Base64.urlEncode(mac)

  private def matches(submitted: String, expected: String): Boolean =
    MessageDigest.isEqual(submitted.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
