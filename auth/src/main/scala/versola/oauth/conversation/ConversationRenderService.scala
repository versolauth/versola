package versola.oauth.conversation

import versola.oauth.authorize.AuthorizeRedirect
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, FormRecord, PrimaryCredential}
import versola.oauth.conversation.model.{ConversationRecord, ConversationStep}
import versola.oauth.jwks.JwksService
import versola.oauth.model.State
import versola.oauth.model.{ConversationCookie, SessionCookie, UserAgentCookie}
import versola.oauth.session.model.SessionInfo
import versola.util.http.Observability
import versola.util.{Base64, Base64Url, CoreConfig, JWT}
import zio.http.{Body, Header, Headers, MediaType, Path, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.json.{jsonDiscriminator, jsonHint}
import zio.{Chunk, Clock, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

trait ConversationRenderService:
  def renderStep(record: ConversationRecord, ifNoneMatch: Option[String], errorKey: Option[String] = None): Task[Response]

  def renderExpired(clientId: ClientId, redirectUri: String, state: Option[String]): Task[Response]

  def renderServiceUnavailable(clientId: ClientId, redirectUri: String, state: Option[String]): Task[Response]

  def renderSubmit(
      result: ConversationResult.Render,
      record: ConversationRecord,
  ): Task[Response]

  def renderLogout(
      logoutUris: List[URL],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
  ): Task[Response]

  def renderLogoutConfirm(
      session: SessionInfo,
      csrfToken: String,
      postLogoutRedirectUri: Option[String],
      state: Option[State],
      uiLocales: Option[List[String]],
  ): Task[Response]

object ConversationRenderService:
  val live = ZLayer.fromFunction(Impl(_, _, _))

  @jsonDiscriminator("type")
  sealed trait StepView derives JsonCodec

  object StepView:
    @jsonHint("credential")
    case class Credential(
        primaryCredentials: List[PrimaryCredential],
        inlinePassword: Boolean,
        passkey: Boolean,
        allowedPhonePrefixes: Option[List[String]],
        passwordRegex: Option[String],
    ) extends StepView
    @jsonHint("password")
    case class Password(passwordRegex: String) extends StepView
    @jsonHint("set-password")
    case class SetPassword(passwordRegex: String) extends StepView
    @jsonHint("otp")
    case class Otp(length: Int, resendAfter: Int, lockedSeconds: Option[Int]) extends StepView
    @jsonHint("passkey-enroll")
    case class PasskeyEnroll(publicKeyOptions: String) extends StepView
    @jsonHint("access-denied")
    case class AccessDenied(redirectUri: String) extends StepView

    @jsonHint("conversation-expired")
    case class ConversationExpired(redirectUri: Option[String]) extends StepView

    @jsonHint("service-unavailable")
    case class ServiceUnavailable(redirectUri: Option[String]) extends StepView

  case class FormConfig(
      step: StepView,
      t: Map[String, String],
      locale: String,
      locales: List[String],
      allT: Map[String, Map[String, String]],
      error: Option[String],
      csrf: String,
  ) derives JsonCodec

  case class LogoutConfirm(
      csrfToken: String,
      postLogoutRedirectUri: Option[String],
      state: Option[String],
  ) extends StepView

  case class SignedOut(
      logoutUris: List[String],
      redirectUri: Option[String],
  ) extends StepView

  private val ThemeDefault = "default"

  case class FormRenderInfo(
      title: String,
      style: String,
      jsCompiled: Option[String],
      config: FormConfig,
      version: Int,
  )

  class Impl(
      config: CoreConfig,
      configuration: OAuthConfigurationService,
      jwksService: JwksService,
  ) extends ConversationRenderService:
    override def renderStep(record: ConversationRecord, ifNoneMatch: Option[String], errorKey: Option[String] = None): Task[Response] =
      for
        client <- configuration.find(record.clientId)
        themeId = client.map(_.theme).getOrElse(ThemeDefault)
        css <- themeCss(themeId)
        maybeInfo <-
          formFor(record.step, record.clientId, record.uiLocales, record.redirectUri, record.state, record.csrfToken, errorOverride = errorKey)
        response <- maybeInfo match
          case None =>
            ZIO.succeed(htmlResponse(notFoundPage(css), Status.NotFound))
          case Some(info) =>
            val body = solidPage(info, css)
            val etag = etagFor(body)
            if ifNoneMatch.contains(etag) then
              ZIO.succeed(Response.status(Status.NotModified))
            else
              ZIO.succeed(
                htmlResponse(body)
                  .addHeader(Header.Custom("ETag", etag))
                  .addHeader(Header.Custom("Cache-Control", "private, no-cache")),
              )
      yield response

    override def renderExpired(clientId: ClientId, redirectUri: String, state: Option[String]): Task[Response] =
      renderTerminal(
        clientId,
        "conversation-expired",
        StepView.ConversationExpired(returnUri(redirectUri, state, "login_required")),
      )

    override def renderServiceUnavailable(clientId: ClientId, redirectUri: String, state: Option[String]): Task[Response] =
      renderTerminal(
        clientId,
        "service-unavailable",
        StepView.ServiceUnavailable(returnUri(redirectUri, state, "temporarily_unavailable")),
      )

    private def returnUri(
        redirectUri: String,
        state: Option[String],
        error: String,
    ): Option[String] =
      URL.decode(redirectUri).toOption.map: url =>
        url.addQueryParams(List("error" -> error) ++ state.map("state" -> _)).encode

    private def renderTerminal(clientId: ClientId, formId: String, step: StepView): Task[Response] =
      for
        client <- configuration.find(clientId)
        themeId = client.map(_.theme).getOrElse(ThemeDefault)
        css <- themeCss(themeId)
        formOpt <- configuration.getForm(formId)
        locales <- configuration.getLocales
      yield formOpt match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(form) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosenLocale, translations) = pickTranslations(form, None, locales.default, activeCodes)
          val info = FormRenderInfo(
            title = pageTitle(translations),
            style = form.style,
            jsCompiled = form.jsCompiled,
            config = FormConfig(
              step = step,
              t = translations,
              locale = chosenLocale,
              locales = (form.localizations.keySet & activeCodes).toList.sorted,
              allT = form.localizations,
              error = None,
              csrf = "",
            ),
            version = form.version,
          )
          htmlResponse(solidPage(info, css))
            .addHeader(Header.Custom("Cache-Control", "private, no-cache"))

    private def etagFor(body: String): String =
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8))
      "\"" + Base64Url.encode(digest) + "\""

    override def renderSubmit(
        result: ConversationResult.Render,
        record: ConversationRecord,
    ): Task[Response] =
      result match
        case ConversationResult.RenderStep(step) =>
          ZIO.succeed(Response.seeOther(URL.root.copy(path = Path.root / "challenge")))

        case ConversationResult.ServiceUnavailable =>
          renderStep(record, ifNoneMatch = None, errorKey = Some("service_unavailable"))

        case ConversationResult.BadRequest | ConversationResult.WriteConflict =>
          ZIO.succeed(Response.seeOther(URL.root.copy(path = Path.root / "challenge")))

        case ConversationResult.Complete(redirectUri, state, code, sessionId, idTokenData, userAgentId, userAgentData) =>
          val encodedCode = Base64Url.encode(code)
          for
            sessionTtl <- configuration.getSessionTtl(record.clientId)
            userAgentTtl <- configuration.getUserAgentTtl(record.clientId)
            idToken <- idTokenData match
              case Some(data) =>
                for
                  signingKey <- jwksService.getPublicKeys.map(_.active)
                  cHash = JWT.leftHalfHash(encodedCode, signingKey.algorithm)
                  dataWithCHash = data.copy(claims = data.claims + ("c_hash" -> Json.Str(cHash)))
                  token <- serializeIdToken(dataWithCHash, signingKey)
                yield Some(token)
              case None => ZIO.none
            redirectUrl = AuthorizeRedirect.responseUrl(redirectUri, encodedCode, state, idToken)
          yield Response.seeOther(redirectUrl)
            .addCookie(
              SessionCookie(
                value = sessionId,
                ttl = sessionTtl,
                secret = config.security.sessionCookieSecret,
              ),
            )
            .addCookie(
              UserAgentCookie(
                id = userAgentId,
                data = userAgentData,
                ttl = userAgentTtl,
                secret = config.security.userAgentCookieSecret,
              ),
            )
            .addCookie(ConversationCookie.expired)

    override def renderLogout(
        logoutUris: List[URL],
        postLogoutRedirectUri: Option[URL],
        state: Option[String],
    ): Task[Response] =
      val redirectUri = postLogoutRedirectUri
        .map(url => state.fold(url)(url.addQueryParam("state", _)).encode)
      for
        css <- themeCss(ThemeDefault)
        formOpt <- configuration.getForm("signed-out")
        locales <- configuration.getLocales
      yield formOpt match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(form) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosenLocale, translations) = pickTranslations(form, None, locales.default, activeCodes)
          val info = FormRenderInfo(
            title = pageTitle(translations),
            style = form.style,
            jsCompiled = form.jsCompiled,
            config = FormConfig(
              step = SignedOut(logoutUris.map(_.encode), redirectUri),
              t = translations,
              locale = chosenLocale,
              locales = (form.localizations.keySet & activeCodes).toList.sorted,
              allT = form.localizations,
              error = None,
              csrf = "",
            ),
            version = form.version,
          )
          htmlResponse(solidPage(info, css))
            .addHeader(Header.Custom("Cache-Control", "no-cache, no-store"))
            // logoutUris are rendered as third-party iframes; without this, the browser could
            // leak this page's URL (id_token_hint, post_logout_redirect_uri, state) to those
            // RPs via the Referer header on the iframe requests.
            .addHeader(Header.Custom("Referrer-Policy", "no-referrer"))

    override def renderLogoutConfirm(
        session: SessionInfo,
        csrfToken: String,
        postLogoutRedirectUri: Option[String],
        state: Option[State],
        uiLocales: Option[List[String]],
    ): Task[Response] =
      for
        client <- session.record.clients.headOption.map(_.clientId).fold(ZIO.succeed(None))(configuration.find)
        css <- themeCss(client.map(_.theme).getOrElse(ThemeDefault))
        form <- configuration.getForm("confirm-logout")
        locales <- configuration.getLocales
      yield form match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(value) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosen, translations) = pickTranslations(value, uiLocales, locales.default, activeCodes)
          val info = FormRenderInfo(
            pageTitle(translations),
            value.style,
            value.jsCompiled,
            FormConfig(
              LogoutConfirm(csrfToken, postLogoutRedirectUri, state),
              translations,
              chosen,
              (value.localizations.keySet & activeCodes).toList.sorted,
              value.localizations,
              None,
              csrfToken,
            ),
            value.version,
          )
          htmlResponse(logoutConfirmPage(info, css))

    private def serializeIdToken(data: ConversationResult.IdTokenData, signingKey: JWT.PublicKey): Task[String] =
      val claims = data.claims + ("sid" -> Json.Str(data.sessionId))
      JWT.serialize(
        typ = JWT.Type.JWT,
        claims = JWT.Claims(
          issuer = config.jwt.issuer,
          subject = data.userId.toString,
          audience = List(data.clientId),
          custom = Json.Obj(Chunk.fromIterable(claims)),
        ),
        ttl = 15.minutes,
        signature = JWT.Signature.Asymmetric(
          algorithm = signingKey.algorithm,
          keyId = signingKey.id,
          privateKey = config.jwt.privateKey,
        ),
      )

    private def formFor(
        step: ConversationStep,
        clientId: ClientId,
        locale: Option[List[String]],
        redirectUri: URL,
        state: Option[State],
        csrfToken: String,
        errorOverride: Option[String] = None,
    ): Task[Option[FormRenderInfo]] =
      val formId = step match
        case _: ConversationStep.Credential => "credential"
        case _: ConversationStep.Password => "password"
        case _: ConversationStep.SetPassword => "set-password"
        case _: ConversationStep.Otp => "otp"
        case _: ConversationStep.PasskeyEnroll => "passkey-enroll"
        case ConversationStep.AccessDenied => "access-denied"
      for
        view <- stepView(step, clientId, redirectUri, state)
        formOpt <- configuration.getForm(formId)
        locales <- configuration.getLocales
        errorMessage = errorOverride.orElse(stepErrorKey(step))
        _ <- Observability.setStep(formId)
        _ <- ZIO.foreachDiscard(errorMessage)(Observability.setError(_))
      yield formOpt.map { form =>
        val activeCodes = locales.locales.map(_.code).toSet + locales.default
        val (chosenLocale, translations) = pickTranslations(form, locale, locales.default, activeCodes)
        val allLocales = (form.localizations.keySet & activeCodes).toList.sorted
        FormRenderInfo(
          title = pageTitle(translations),
          style = form.style,
          jsCompiled = form.jsCompiled,
          config = FormConfig(
            step = view,
            t = translations,
            locale = chosenLocale,
            locales = allLocales,
            allT = form.localizations,
            error = errorMessage,
            csrf = csrfToken,
          ),
          version = form.version,
        )
      }

    private def stepErrorKey(step: ConversationStep): Option[String] = step match
      case s: ConversationStep.Otp if s.timesSubmitted > 0 && !s.rateLimitExceeded => Some("otp_wrong")
      case s: ConversationStep.Password if s.rateLimitExceeded => Some("rate_limit_exceeded")
      case s: ConversationStep.Password if s.temporaryExpired && s.timesSubmitted > 0 => Some("temporary_expired")
      case s: ConversationStep.Password if s.timesSubmitted > 0 => Some("password_wrong")
      case s: ConversationStep.Credential if s.passkeyFailed => Some("passkey_failed")
      case s: ConversationStep.Credential if s.loginFailed => Some("login_failed")
      case s: ConversationStep.PasskeyEnroll if s.enrollFailed => Some("enroll_failed")
      case s: ConversationStep.SetPassword if s.rateLimitExceeded => Some("rate_limit_exceeded")
      case s: ConversationStep.SetPassword if s.passwordReused => Some("password_reused")
      case _ => None

    private def pageTitle(translations: Map[String, String]): String =
      translations.getOrElse("page_title", "Sign In")

    private def pickTranslations(
        form: FormRecord,
        locale: Option[List[String]],
        default: String,
        activeCodes: Set[String],
    ): (String, Map[String, String]) =
      val base = form.localizations.getOrElse(default, Map.empty)
      val chosenLocale = locale.getOrElse(Nil).iterator
        .find(code => activeCodes.contains(code) && form.localizations.contains(code))
        .getOrElse(default)
      val chosen = form.localizations.getOrElse(chosenLocale, Map.empty)
      (chosenLocale, base ++ chosen)

    private def themeCss(themeId: String): UIO[String] =
      configuration.getTheme(themeId).flatMap {
        case Some(theme) if theme.css.nonEmpty => ZIO.succeed(theme.css)
        case _ => configuration.getTheme(ThemeDefault).map(_.map(_.css).getOrElse(""))
      }

    private def stepView(
        step: ConversationStep,
        clientId: ClientId,
        redirectUri: URL,
        state: Option[State],
    ): UIO[StepView] =
      step match
        case ConversationStep.Credential(primaryCredentials, inlinePassword, passkey, _, _, _) =>
          for
            allowedPhonePrefixes <-
              if primaryCredentials.contains(PrimaryCredential.phone) then
                configuration.getAllowedPhonePrefixes(clientId).map(Some(_))
              else
                ZIO.none
            passwordRegex <-
              if inlinePassword then configuration.getPasswordRegex.map(Some(_))
              else ZIO.none
          yield StepView.Credential(primaryCredentials, inlinePassword, passkey, allowedPhonePrefixes, passwordRegex)

        case _: ConversationStep.Password =>
          configuration.getPasswordRegex.map(StepView.Password(_))

        case _: ConversationStep.SetPassword =>
          configuration.getPasswordRegex.map(StepView.SetPassword(_))

        case s: ConversationStep.Otp =>
          for
            otp <- configuration.getOtpSettings(clientId)
            now <- Clock.instant
            elapsed = s.lastSentAt.fold(0L)(sentAt => now.getEpochSecond - sentAt.getEpochSecond)
            resendRemaining = math.max(0L, otp.resendAfter.toLong - elapsed).toInt
          yield StepView.Otp(
            length = otp.length,
            resendAfter = resendRemaining,
            lockedSeconds = Option.when(s.lockedSeconds > 0)(s.lockedSeconds),
          )

        case s: ConversationStep.PasskeyEnroll =>
          ZIO.succeed(StepView.PasskeyEnroll(publicKeyOptions = s.publicKeyOptions))

        case ConversationStep.AccessDenied =>
          val params = List("error" -> "access_denied") ++ state.map("state" -> _)
          ZIO.succeed(StepView.AccessDenied(redirectUri = redirectUri.addQueryParams(params).encode))

    private def stepName(step: StepView): String = step match
      case _: StepView.Credential => "credential"
      case _: StepView.Password => "password"
      case _: StepView.SetPassword => "set-password"
      case _: StepView.Otp => "otp"
      case _: StepView.PasskeyEnroll => "passkey-enroll"
      case _: StepView.AccessDenied => "access-denied"
      case _: StepView.ConversationExpired => "conversation-expired"
      case _: StepView.ServiceUnavailable => "service-unavailable"
      case _: LogoutConfirm => "confirm-logout"
      case _: SignedOut => "signed-out"

    private def logoutConfirmPage(info: FormRenderInfo, themeCss: String): String =
      s"""<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>${info.title}</title><style>$themeCss ${info.style}</style><script>window.__VERSOLA_FORM__ = ${info.config.toJson};</script></head><body><div id="versola-form-root"></div><script>${info.jsCompiled.getOrElse(
          "",
        )}</script></body></html>"""

    private def solidPage(info: FormRenderInfo, themeCss: String): String =
      s"""<!DOCTYPE html>
         |<html lang="en">
         |  <head>
         |    <meta charset="UTF-8">
         |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
         |    <meta name="versola-step" content="${stepName(info.config.step)}">
         |    <title>${info.title}</title>
         |    <style>
         |      $themeCss
         |      ${info.style}
         |    </style>
         |    <script>
         |      window.__VERSOLA_FORM__ = ${info.config.toJson};
         |    </script>
         |  </head>
         |  <body>
         |    <div id="versola-form-root"></div>
         |    <script>
         |      ${info.jsCompiled.getOrElse("")}
         |    </script>
         |  </body>
         |</html>""".stripMargin

    private def notFoundPage(themeCss: String): String =
      s"""<!DOCTYPE html>
         |<html lang="en">
         |  <head>
         |    <meta charset="UTF-8">
         |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
         |    <title>Page not found</title>
         |    <style>
         |      $themeCss
         |    </style>
         |  </head>
         |  <body>
         |    <div class="container">
         |      <h1>Page not found</h1>
         |      <p>The sign-in form is not available.</p>
         |    </div>
         |  </body>
         |</html>""".stripMargin

    private def htmlResponse(content: String, status: Status = Status.Ok): Response =
      Response(
        status = status,
        headers = Headers(Header.ContentType(MediaType.text.html)),
        body = Body.fromString(content),
      )
