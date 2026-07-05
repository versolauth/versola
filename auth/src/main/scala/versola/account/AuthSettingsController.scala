package versola.account

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId}
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.jwks.JwksService
import versola.oauth.model.AccessTokenPayload
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SessionId
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.http.Controller
import versola.util.{Base64, Base64Url, CoreConfig, JWT, MAC}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

object AuthSettingsController extends Controller:

  type Env =
    Tracing &
      JwksService &
      CoreConfig &
      SessionRepository &
      PasskeyRepository &
      WebAuthnService &
      OAuthConfigurationService &
      UserRepository

  def routes: Routes[Env, Throwable] = Routes(
    getPageRoute,
    logoutSessionRoute,
    deletePasskeyRoute,
    registerPasskeyRoute,
  )

  // -------------------------------------------------------------------------
  // View models injected into window.__VERSOLA_FORM__
  // -------------------------------------------------------------------------

  private case class SessionView(
      id: String,
      clientId: String,
      platform: String,
      os: Option[String],
      browser: Option[String],
      version: Option[String],
      createdAt: String,
  ) derives JsonCodec

  private case class PasskeyView(
      id: String,
      name: Option[String],
      deviceType: String,
      backedUp: Boolean,
      backupEligible: Boolean,
      createdAt: String,
      lastUsedAt: Option[String],
  ) derives JsonCodec

  private case class AuthSettingsConfig(
      sessions: List[SessionView],
      passkeys: List[PasskeyView],
      passkeyRegistration: Option[String],
      t: Map[String, String],
      locale: String,
      locales: List[String],
      allT: Map[String, Map[String, String]],
      error: Option[String],
  ) derives JsonCodec

  // -------------------------------------------------------------------------
  // GET /auth-settings — render the account settings page
  // -------------------------------------------------------------------------

  val getPageRoute =
    Method.GET / "auth-settings" -> handler { (request: Request) =>
      (for
        (userId, clientId) <- authenticateGet(request)
        secret             <- ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret)

        // Fetch sessions and passkeys for this user
        sessions <- ZIO.serviceWithZIO[SessionRepository](_.findByUserIdWithId(userId))
        passkeys <- ZIO.serviceWithZIO[PasskeyRepository](_.listByUser(userId))

        // Optionally start a passkey registration ceremony
        passkeySettings <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasskeySettings(clientId))
        displayName     <- ZIO.serviceWithZIO[UserRepository](_.find(userId)).map: userOpt =>
          userOpt.flatMap(u => u.login.map(_.toString).orElse(u.email.map(_.toString)).orElse(u.phone.map(_.toString)))
            .getOrElse(userId.toString)
        passkeyResult <- passkeySettings match
          case Some(settings) =>
            ZIO.serviceWithZIO[WebAuthnService](_.startRegistration(settings, userId, displayName))
              .map(c => Some(c))
              .catchAll(_ => ZIO.none)
          case None => ZIO.none

        // Fetch form, theme and locales from Central
        formOpt <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getForm("auth-settings"))
        client  <- ZIO.serviceWithZIO[OAuthConfigurationService](_.find(clientId))
        themeId = client.map(_.theme).getOrElse("default")
        css     <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getTheme(themeId)).flatMap:
          case Some(t) if t.css.nonEmpty => ZIO.succeed(t.css)
          case _ => ZIO.serviceWithZIO[OAuthConfigurationService](_.getTheme("default")).map(_.map(_.css).getOrElse(""))
        locales <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getLocales)

        response <- formOpt match
          case None =>
            ZIO.succeed(Response.notFound)
          case Some(form) =>
            val activeCodes  = (locales.locales.map(_.code).toSet + locales.default) & form.localizations.keySet
            val allLocales   = activeCodes.toList.sorted
            val translations = form.localizations.getOrElse(locales.default, Map.empty)

            val config = AuthSettingsConfig(
              sessions = sessions.map: (id, rec) =>
                SessionView(
                  id        = Base64Url.encode(id),
                  clientId  = rec.clientId,
                  platform  = rec.userAgent.platform,
                  os        = rec.userAgent.os,
                  browser   = rec.userAgent.browser,
                  version   = rec.userAgent.version,
                  createdAt = rec.createdAt.toString,
                ),
              passkeys = passkeys.toList.map: r =>
                PasskeyView(
                  id             = Base64Url.encode(r.id),
                  name           = r.name,
                  deviceType     = r.deviceType.toString,
                  backedUp       = r.backedUp,
                  backupEligible = r.backupEligible,
                  createdAt      = r.createdAt.toString,
                  lastUsedAt     = r.lastUsedAt.map(_.toString),
                ),
              passkeyRegistration = passkeyResult.map(_.publicKeyOptions),
              t         = translations,
              locale    = locales.default,
              locales   = allLocales,
              allT      = form.localizations,
              error     = None,
            )

            val pageTitle = translations.getOrElse("page_title", "Account Settings")
            val html = renderPage(form.style, form.jsCompiled, css, config, pageTitle)
            val authCookie = AuthSettingsCookie.responseCookie(AuthSettingsCookie(userId, clientId), secret)
            val baseResponse = htmlResponse(html).addCookie(authCookie)
            val withRegCookie = passkeyResult match
              case Some(ceremony) =>
                baseResponse.addCookie(
                  PasskeyRegistrationCookie.responseCookie(PasskeyRegistrationCookie(ceremony.request), secret)
                )
              case None => baseResponse
            ZIO.succeed(withRegCookie)
      yield response)
        .catchAll:
          case versola.util.http.Unauthorized => ZIO.succeed(Response.unauthorized)
          case ex: Throwable                  => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // POST /auth-settings/sessions/logout
  // Form fields: id=<base64url session MAC>
  // -------------------------------------------------------------------------

  val logoutSessionRoute =
    Method.POST / "auth-settings" / "sessions" / "logout" -> handler { (request: Request) =>
      (for
        (userId, _) <- authenticateCookie(request)
        form        <- request.body.asURLEncodedForm.mapError(_ => versola.util.http.Unauthorized)
        rawId       <- ZIO.fromOption(form.get("id").flatMap(_.stringValue))
          .orElseFail(versola.util.http.Unauthorized)
        idBytes     <- ZIO.attempt(Base64.urlDecode(rawId))
          .mapError(_ => versola.util.http.Unauthorized)
        sessionId   = MAC(idBytes)
        _           <- ZIO.serviceWithZIO[SessionRepository](_.invalidate(sessionId, userId))
      yield Response.seeOther(URL.root / "auth-settings"))
        .catchAll:
          case versola.util.http.Unauthorized => ZIO.succeed(Response.unauthorized)
          case ex: Throwable                  => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // POST /auth-settings/passkeys/delete
  // Form fields: id=<base64url credential id>
  // -------------------------------------------------------------------------

  val deletePasskeyRoute =
    Method.POST / "auth-settings" / "passkeys" / "delete" -> handler { (request: Request) =>
      (for
        (userId, _) <- authenticateCookie(request)
        form        <- request.body.asURLEncodedForm.mapError(_ => versola.util.http.Unauthorized)
        rawId       <- ZIO.fromOption(form.get("id").flatMap(_.stringValue))
          .orElseFail(versola.util.http.Unauthorized)
        credentialId <- ZIO.fromEither(CredentialId.fromBase64Url(rawId))
          .mapError(_ => versola.util.http.Unauthorized)
        _ <- ZIO.serviceWithZIO[PasskeyRepository](_.deleteByUser(credentialId, userId))
      yield Response.seeOther(URL.root / "auth-settings"))
        .catchAll:
          case versola.util.http.Unauthorized => ZIO.succeed(Response.unauthorized)
          case ex: Throwable                  => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // POST /auth-settings/passkeys/register
  // Form fields: response=<webauthn JSON>, name=<optional name>
  // -------------------------------------------------------------------------

  val registerPasskeyRoute =
    Method.POST / "auth-settings" / "passkeys" / "register" -> handler { (request: Request) =>
      (for
        (userId, clientId) <- authenticateCookie(request)
        secret             <- ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret)

        // Retrieve ceremony request from cookie
        regCookie <- ZIO.fromOption(request.cookie(PasskeyRegistrationCookie.name))
          .orElseFail(versola.util.http.Unauthorized)
        ceremony  <- ZIO.fromEither(PasskeyRegistrationCookie.parse(regCookie.content, secret))
          .orElseFail(versola.util.http.Unauthorized)

        form        <- request.body.asURLEncodedForm.mapError(_ => versola.util.http.Unauthorized)
        response    <- ZIO.fromOption(form.get("response").flatMap(_.stringValue))
          .orElseFail(versola.util.http.Unauthorized)
        name         = form.get("name").flatMap(_.stringValue).filter(_.nonEmpty)

        passkeySettings <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasskeySettings(clientId))
        settings        <- ZIO.fromOption(passkeySettings)
          .orElseFail(new RuntimeException("passkey not configured for this client"))

        _ <- ZIO.serviceWithZIO[WebAuthnService](
          _.finishRegistration(settings, userId, ceremony.request, response, name)
        )
      yield Response.seeOther(URL.root / "auth-settings"))
        .catchAll:
          case versola.util.http.Unauthorized => ZIO.succeed(Response.unauthorized)
          case ex: Throwable                  => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // Auth helpers
  // -------------------------------------------------------------------------

  /** Accept Bearer token OR SSO_ACCOUNT cookie. Used for GET. */
  private def authenticateGet(
      request: Request,
  ): ZIO[JwksService & CoreConfig, versola.util.http.Unauthorized.type, (UserId, ClientId)] =
    request.header(Header.Authorization) match
      case Some(Header.Authorization.Bearer(token)) =>
        for
          keys    <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys)
          payload <- JWT.deserialize[AccessTokenPayload](token.stringValue, keys, JWT.Type.AccessToken)
            .orElseFail(versola.util.http.Unauthorized)
          userId  <- ZIO.fromOption(payload.userId).orElseFail(versola.util.http.Unauthorized)
          _       <- ZIO.fail(versola.util.http.Unauthorized)
            .unless(payload.scope.contains(ScopeToken.AccountSettings))
        yield (userId, payload.clientId)
      case _ =>
        authenticateCookie(request)

  /** Accept only SSO_ACCOUNT cookie. Used for action endpoints. */
  private def authenticateCookie(
      request: Request,
  ): ZIO[CoreConfig, versola.util.http.Unauthorized.type, (UserId, ClientId)] =
    ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret).flatMap: secret =>
      ZIO.fromOption(request.cookie(AuthSettingsCookie.name))
        .orElseFail(versola.util.http.Unauthorized)
        .flatMap: cookie =>
          ZIO.fromEither(AuthSettingsCookie.parse(cookie.content, secret))
            .orElseFail(versola.util.http.Unauthorized)
        .map(c => (c.userId, c.clientId))

  // -------------------------------------------------------------------------
  // Rendering helpers
  // -------------------------------------------------------------------------

  private def renderPage(
      style: String,
      jsCompiled: Option[String],
      themeCss: String,
      config: AuthSettingsConfig,
      title: String,
  ): String =
    // Escape </script> in the JSON payload to prevent script-breakout XSS.
    val safeJson = config.toJson.replace("</script>", "<\\/script>")
    s"""<!DOCTYPE html>
       |<html lang="en">
       |  <head>
       |    <meta charset="UTF-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |    <title>$title</title>
       |    <style>
       |      $themeCss
       |      $style
       |    </style>
       |    <script>
       |      window.__VERSOLA_FORM__ = $safeJson;
       |    </script>
       |  </head>
       |  <body>
       |    <div id="versola-form-root"></div>
       |    <script>
       |      ${jsCompiled.getOrElse("")}
       |    </script>
       |  </body>
       |</html>""".stripMargin

  private def htmlResponse(content: String): Response =
    Response(
      status  = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.html)),
      body    = Body.fromString(content),
    )
