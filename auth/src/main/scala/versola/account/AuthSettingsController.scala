package versola.account

import versola.auth.model.CredentialId
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.jwks.JwksService
import versola.oauth.model.AccessTokenPayload
import versola.user.model.UserId
import versola.util.http.{Controller, Unauthorized}
import versola.util.{Base64Url, CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.util.UUID

object AuthSettingsController extends Controller:

  type Env =
    Tracing &
      JwksService &
      CoreConfig &
      AuthSettingsService

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
        pageDataOpt        <- ZIO.serviceWithZIO[AuthSettingsService](_.getPageData(userId, clientId))
        response <- pageDataOpt match
          case None       => ZIO.succeed(Response.notFound)
          case Some(data) =>
            val config = AuthSettingsConfig(
              sessions = data.sessions.map: (publicId, rec) =>
                SessionView(
                  id        = publicId.toString,
                  clientId  = rec.clientId.toString,
                  platform  = rec.userAgent.platform,
                  os        = rec.userAgent.os,
                  browser   = rec.userAgent.browser,
                  version   = rec.userAgent.version,
                  createdAt = rec.createdAt.toString,
                ),
              passkeys = data.passkeys.toList.map: r =>
                PasskeyView(
                  id             = Base64Url.encode(r.id),
                  name           = r.name,
                  deviceType     = r.deviceType.toString,
                  backedUp       = r.backedUp,
                  backupEligible = r.backupEligible,
                  createdAt      = r.createdAt.toString,
                  lastUsedAt     = r.lastUsedAt.map(_.toString),
                ),
              passkeyRegistration = data.passkeyResult.map(_.publicKeyOptions),
              t                   = data.translations,
              locale              = data.locale,
              locales             = data.locales,
              allT                = data.allTranslations,
              error               = None,
            )
            val safeTitle = data.pageTitle
              .replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")
            val html        = renderPage(data.style, data.jsCompiled, data.css, config, safeTitle)
            val authCookie  = AuthSettingsCookie.responseCookie(AuthSettingsCookie(userId, clientId), secret)
            val baseResp    = htmlResponse(html).addCookie(authCookie)
            val withRegCookie = data.passkeyResult match
              case Some(ceremony) =>
                baseResp.addCookie(
                  PasskeyRegistrationCookie.responseCookie(PasskeyRegistrationCookie(ceremony.request), secret)
                )
              case None => baseResp
            ZIO.succeed(withRegCookie)
      yield response)
        .catchAll:
          case Unauthorized  => ZIO.succeed(Response.unauthorized)
          case ex: Throwable => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // POST /auth-settings/sessions/logout
  // Form fields: id=<public session UUID>
  // -------------------------------------------------------------------------

  val logoutSessionRoute =
    Method.POST / "auth-settings" / "sessions" / "logout" -> handler { (request: Request) =>
      (for
        _               <- authenticateCookie(request)
        form            <- request.body.asURLEncodedForm.mapError(_ => Unauthorized)
        rawId           <- ZIO.fromOption(form.get("id").flatMap(_.stringValue))
          .orElseFail(Unauthorized)
        publicSessionId <- ZIO.attempt(UUID.fromString(rawId)).orElseFail(Unauthorized)
        _               <- ZIO.serviceWithZIO[AuthSettingsService](_.invalidateSession(publicSessionId))
      yield Response.seeOther(URL.root / "auth-settings"))
        .catchAll:
          case Unauthorized  => ZIO.succeed(Response.unauthorized)
          case ex: Throwable => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // POST /auth-settings/passkeys/delete
  // Form fields: id=<base64url credential id>
  // -------------------------------------------------------------------------

  val deletePasskeyRoute =
    Method.POST / "auth-settings" / "passkeys" / "delete" -> handler { (request: Request) =>
      (for
        (userId, _)  <- authenticateCookie(request)
        form         <- request.body.asURLEncodedForm.mapError(_ => Unauthorized)
        rawId        <- ZIO.fromOption(form.get("id").flatMap(_.stringValue))
          .orElseFail(Unauthorized)
        credentialId <- ZIO.fromEither(CredentialId.fromBase64Url(rawId))
          .mapError(_ => Unauthorized)
        _            <- ZIO.serviceWithZIO[AuthSettingsService](_.deletePasskey(credentialId, userId))
      yield Response.seeOther(URL.root / "auth-settings"))
        .catchAll:
          case Unauthorized  => ZIO.succeed(Response.unauthorized)
          case ex: Throwable => ZIO.fail(ex)
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
        regCookie          <- ZIO.fromOption(request.cookie(PasskeyRegistrationCookie.name))
          .orElseFail(Unauthorized)
        ceremony           <- ZIO.fromEither(PasskeyRegistrationCookie.parse(regCookie.content, secret))
          .orElseFail(Unauthorized)
        form               <- request.body.asURLEncodedForm.mapError(_ => Unauthorized)
        response           <- ZIO.fromOption(form.get("response").flatMap(_.stringValue))
          .orElseFail(Unauthorized)
        name                = form.get("name").flatMap(_.stringValue).filter(_.nonEmpty)
        _                  <- ZIO.serviceWithZIO[AuthSettingsService](
          _.finishPasskeyRegistration(userId, clientId, ceremony.request, response, name)
        )
        clearRegCookie      = PasskeyRegistrationCookie.clear(secret)
      yield Response.seeOther(URL.root / "auth-settings").addCookie(clearRegCookie))
        .catchAll:
          case Unauthorized  => ZIO.succeed(Response.unauthorized)
          case ex: Throwable => ZIO.fail(ex)
    }

  // -------------------------------------------------------------------------
  // Auth helpers
  // -------------------------------------------------------------------------

  /** Accept Bearer token OR SSO_ACCOUNT cookie. Used for GET. */
  private def authenticateGet(
      request: Request,
  ): ZIO[JwksService & CoreConfig, Unauthorized.type, (UserId, ClientId)] =
    request.header(Header.Authorization) match
      case Some(Header.Authorization.Bearer(token)) =>
        for
          keys    <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys)
          payload <- JWT.deserialize[AccessTokenPayload](token.stringValue, keys, JWT.Type.AccessToken)
            .orElseFail(Unauthorized)
          userId  <- ZIO.fromOption(payload.userId).orElseFail(Unauthorized)
          _       <- ZIO.fail(Unauthorized)
            .unless(payload.scope.contains(ScopeToken.AccountSettings))
        yield (userId, payload.clientId)
      case _ =>
        authenticateCookie(request)

  /** Accept only SSO_ACCOUNT cookie. Used for action endpoints. */
  private def authenticateCookie(
      request: Request,
  ): ZIO[CoreConfig, Unauthorized.type, (UserId, ClientId)] =
    ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret).flatMap: secret =>
      ZIO.fromOption(request.cookie(AuthSettingsCookie.name))
        .orElseFail(Unauthorized)
        .flatMap: cookie =>
          ZIO.fromEither(AuthSettingsCookie.parse(cookie.content, secret))
            .orElseFail(Unauthorized)
        .map(c => (c.userId, c.clientId))

  // -------------------------------------------------------------------------
  // Rendering helpers
  // -------------------------------------------------------------------------

  private def renderPage(
      style: String,
      jsCompiled: Option[String],
      themeCss: String,
      config: AuthSettingsConfig,
      safeTitle: String,
  ): String =
    // Escape characters that can break an inline <script> block:
    //   < prevents </script> breakout
    //   U+2028 / U+2029 are line terminators in JS but valid in JSON strings
    val safeJson = config.toJson
      .replace("<", "\\u003c")
      .replace(" ", "\\u2028")
      .replace(" ", "\\u2029")
    s"""<!DOCTYPE html>
       |<html lang="${config.locale}">
       |  <head>
       |    <meta charset="UTF-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |    <title>$safeTitle</title>
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
      headers = Headers(
        Header.ContentType(MediaType.text.html),
        Header.Custom("Cache-Control", "no-store"),
      ),
      body    = Body.fromString(content),
    )
