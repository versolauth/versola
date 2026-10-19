package versola.oauth.conversation

import versola.auth.model.PasskeyName
import versola.oauth.authorize.model.ResponseMode
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.authorize.{AuthorizationResponseService, AuthorizeRedirect}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, FormRecord, Locales, OAuthClientRecord, PhoneRegion, PrimaryCredential, ScopeToken, TenantLogo}
import versola.oauth.consent.ConsentService
import versola.oauth.conversation.model.{ConversationRecord, ConversationStep}
import versola.oauth.jwks.JwksService
import versola.oauth.model.State
import versola.oauth.model.{ConversationCookie, SessionCookie, UserAgentCookie}
import versola.oauth.session.model.SessionInfo
import versola.util.http.Observability
import versola.util.theme.{ResolvedTokens, ThemeCss, ThemeOverrides, ThemePresets}
import versola.util.{Base64, Base64Url, CoreConfig, Email, JWT, Phone, encodeQueryParam, escapeCssForStyle, escapeHtml}
import zio.http.{Body, Header, Headers, MediaType, Path, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.json.{jsonDiscriminator, jsonHint}
import zio.{Chunk, Clock, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

trait ConversationRenderService:
  def renderStep(record: ConversationRecord, ifNoneMatch: Option[String], errorKey: Option[String] = None): Task[Response]

  def renderExpired(
      clientId: ClientId,
      redirectUri: String,
      state: Option[String],
      responseMode: ResponseMode,
      uiLocales: Option[List[String]],
  ): Task[Response]

  def renderServiceUnavailable(
      clientId: ClientId,
      redirectUri: String,
      state: Option[String],
      responseMode: ResponseMode,
      uiLocales: Option[List[String]],
  ): Task[Response]

  /** `uiLocale` is the language the form was submitted in; the redirect to the next step carries it
    * so that step renders in it, since nothing but the URL ties the two requests together.
    */
  def renderSubmit(
      result: ConversationResult.Render,
      record: ConversationRecord,
      uiLocale: Option[String],
  ): Task[Response]

  /** `clientId` is the app the session was last used by, when known: its theme and appearance overrides dress the page. */
  def renderLogout(
      logoutUris: List[URL],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
      uiLocales: Option[List[String]],
      clientId: Option[ClientId],
  ): Task[Response]

  def renderLogoutConfirm(
      session: SessionInfo,
      csrfToken: String,
      postLogoutRedirectUri: Option[String],
      state: Option[State],
      uiLocales: Option[List[String]],
  ): Task[Response]

  /** Renders the self-service account page. The data is resolved by the caller and inlined
    * into the page, so the first paint needs no request of its own. */
  def renderAccount(
      clientId: ClientId,
      view: ConversationRenderService.StepView.AccountSettings,
      uiLocales: Option[List[String]],
  ): Task[Response]

object ConversationRenderService:

  /** Applies the visitor's own Auto / Light / Dark choice (`localStorage`, key `versola.colorScheme`, written by the forms'
    * `shared/scheme-toggle.tsx`) before the first paint. It sits after the `<style>` block because it reads the computed
    * `--scheme-locked`: a theme that fixes the scheme sets it to 1, and then the stored choice is not applied (it is left
    * in storage, since another client's theme may not lock). Constant: no configuration and no user data go into it.
    * Storage may be blocked, hence the try/catch; the page then simply follows the device.
    */
  val SchemeScript: String =
    "<script>(function(){try{var d=document.documentElement;" +
      "if(getComputedStyle(d).getPropertyValue('--scheme-locked').trim()==='1')return;" +
      "var v=localStorage.getItem('versola.colorScheme');" +
      "if(v==='light'||v==='dark')d.style.colorScheme=v}catch(e){}})()</script>"
  val live = ZLayer.fromFunction(Impl(_, _, _, _))

  @jsonDiscriminator("type")
  sealed trait StepView derives JsonCodec

  object StepView:
    @jsonHint("credential")
    case class Credential(
        primaryCredentials: List[PrimaryCredential],
        inlinePassword: Boolean,
        passkey: Boolean,
        phoneRegions: Option[List[PhoneRegion]],
        passwordRegex: Option[String],
        registration: Boolean,
    ) extends StepView
    @jsonHint("password")
    case class Password(passwordRegex: String) extends StepView
    @jsonHint("set-password")
    case class SetPassword(passwordRegex: String) extends StepView
    @jsonHint("otp")
    case class Otp(length: Int, resendAfter: Int, lockedSeconds: Option[Int], destination: Option[String]) extends StepView
    @jsonHint("passkey-enroll")
    case class PasskeyEnroll(publicKeyOptions: String) extends StepView

    /** One row on the consent card. `description` and `claims` preserve the initial server-side
      * resolution, while the localization maps let the form react to a locale change in-place. */
    case class ConsentScope(
        scope: ScopeToken,
        description: Option[String],
        descriptionLocalizations: Map[String, String],
        claims: List[String],
        claimLocalizations: List[Map[String, String]],
        deselectable: Boolean,
    ) derives JsonCodec

    /** Who is signed in, shown on the consent card so the user sees whose account is being shared. */
    case class ConsentUser(
        name: Option[String],
        /** The email or phone number, masked. */
        identifier: Option[String],
        /** An https URL, from the user's `picture` claim. */
        picture: Option[String],
    ) derives JsonCodec

    @jsonHint("consent")
    case class Consent(
        clientName: Option[String],
        /** The client's name in every language, see [[ClientBrand]]. */
        clientNames: Map[String, String],
        logoUri: Option[String],
        policyUri: Option[String],
        tosUri: Option[String],
        scopes: List[ConsentScope],
        allowPartial: Boolean,
        denyUri: String,
        user: Option[ConsentUser],
    ) extends StepView

    /** One active session of the user, as rendered on the account page. `id` is the
      * protocol-visible [[versola.oauth.session.model.PublicSessionId]]: it carries no
      * authority (see its scaladoc), and revocation is authorized against the caller's own
      * session list rather than against knowledge of this value. */
    case class AccountSession(
        id: String,
        platform: Option[String],
        os: Option[String],
        browser: Option[String],
        version: Option[String],
        createdAt: Instant,
        expiresAt: Instant,
        /** The session the page is being viewed from, resolved from the access token's `sid`,
            * so account settings can keep its revocation action unavailable. */
        current: Boolean,
    ) derives JsonCodec

    case class AccountPasskey(
        id: String,
        name: Option[PasskeyName],
        backedUp: Boolean,
        lastUsedAt: Option[Instant],
        createdAt: Instant,
    ) derives JsonCodec

    @jsonHint("auth-settings")
    case class AccountSettings(
        sessions: List[AccountSession],
        passkeys: List[AccountPasskey],
        /** The user's phone number and email, masked, so the page can say what a change replaces. */
        phone: Option[String],
        email: Option[String],
    ) extends StepView

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
      logo: Option[TenantLogo],
      /** The client the user is signing in to; forms show it inside the card for a partner's app. */
      client: Option[ClientBrand],
      /** The language each code is shown as in the switcher, as configured under Locales. */
      localeNames: Map[String, String],
  ) derives JsonCodec

  /** What a form shows of the client: its name and logo, and whether it is a partner's (third-party) app, which has
    * a consent flow; the identity provider's own apps are not announced inside the card.
    *
    * Contract for the name: `names` is the client's whole `clientName` map (every language, keys as stored, names
    * cleaned by [[cleanName]]) and `name` is the one the server picked for the first render. A form that lets the user
    * switch language picks again from `names` with the same rule as the server's `pickLocalized`: the locale matched
    * case-insensitively, else the same language without the region (`pt-BR` -> `pt`), else the tenant's default locale,
    * else any entry. The same holds for the consent step's `clientName` / `clientNames`.
    */
  case class ClientBrand(
      name: Option[String],
      names: Map[String, String],
      logo: Option[String],
      partner: Boolean,
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
      responseService: AuthorizationResponseService,
  ) extends ConversationRenderService:
    override def renderStep(record: ConversationRecord, ifNoneMatch: Option[String], errorKey: Option[String] = None): Task[Response] =
      for
        client <- configuration.find(record.clientId)
        themeId = client.map(_.theme).getOrElse(ThemeDefault)
        css <- themeCss(themeId, client.flatMap(_.themeOverrides))
        logo <- tenantLogo(client)
        maybeInfo <-
          formFor(
            record.step,
            record.credential,
            record.clientId,
            record.uiLocales,
            record.redirectUri,
            record.state,
            record.csrfToken,
            availableClaims = availableClaimNames(record),
            consentUser = record.step match
              case _: ConversationStep.Consent => consentUser(record)
              case _ => None,
            errorOverride = errorKey,
            responseMode = record.responseMode,
          )
        response <- maybeInfo match
          case None =>
            ZIO.succeed(htmlResponse(notFoundPage(css), Status.NotFound))
          case Some(info) =>
            val body = solidPage(info, css, logo)
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

    override def renderExpired(
        clientId: ClientId,
        redirectUri: String,
        state: Option[String],
        responseMode: ResponseMode,
        uiLocales: Option[List[String]],
    ): Task[Response] =
      returnUri(clientId, redirectUri, state, "login_required", responseMode).flatMap: uri =>
        renderTerminal(
          clientId,
          "conversation-expired",
          StepView.ConversationExpired(uri),
          uiLocales,
        )

    override def renderServiceUnavailable(
        clientId: ClientId,
        redirectUri: String,
        state: Option[String],
        responseMode: ResponseMode,
        uiLocales: Option[List[String]],
    ): Task[Response] =
      returnUri(clientId, redirectUri, state, "temporarily_unavailable", responseMode).flatMap: uri =>
        renderTerminal(
          clientId,
          "service-unavailable",
          StepView.ServiceUnavailable(uri),
          uiLocales,
        )

    /** The link back to the client for an error the user is shown a page about first. It is
      * an authorization error response like any other, so under JARM it is signed rather than
      * spelled out in the URI.
      */
    private def returnUri(
        clientId: ClientId,
        redirectUri: String,
        state: Option[String],
        error: String,
        responseMode: ResponseMode,
    ): Task[Option[String]] =
      URL.decode(redirectUri).toOption match
        case None => ZIO.none
        case Some(url) =>
          val params = List("error" -> error, "iss" -> config.jwt.issuer) ++ state.map("state" -> _)
          responseService.redirect(clientId, url, responseMode, params).map(uri => Some(uri.encode))

    private def renderTerminal(clientId: ClientId, formId: String, step: StepView, uiLocales: Option[List[String]]): Task[Response] =
      for
        client <- configuration.find(clientId)
        themeId = client.map(_.theme).getOrElse(ThemeDefault)
        css <- themeCss(themeId, client.flatMap(_.themeOverrides))
        logo <- tenantLogo(client)
        formOpt <- configuration.getForm(formId)
        locales <- configuration.getLocales
      yield formOpt match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(form) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosenLocale, translations) = pickTranslations(form, uiLocales, locales.default, activeCodes)
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
              localeNames = localeNames(locales, (form.localizations.keySet & activeCodes).toList.sorted),
              logo = None,
              client = None,
            ),
            version = form.version,
          )
          htmlResponse(solidPage(info, css, logo))
            .addHeader(Header.Custom("Cache-Control", "private, no-cache"))

    override def renderAccount(
        clientId: ClientId,
        view: StepView.AccountSettings,
        uiLocales: Option[List[String]],
    ): Task[Response] =
      for
        client <- configuration.find(clientId)
        css <- themeCss(client.map(_.theme).getOrElse(ThemeDefault), client.flatMap(_.themeOverrides))
        logo <- tenantLogo(client)
        formOpt <- configuration.getForm("auth-settings")
        locales <- configuration.getLocales
      yield formOpt match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(form) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosenLocale, translations) = pickTranslations(form, uiLocales, locales.default, activeCodes)
          val info = FormRenderInfo(
            title = pageTitle(translations),
            style = form.style,
            jsCompiled = form.jsCompiled,
            config = FormConfig(
              step = view,
              t = translations,
              locale = chosenLocale,
              locales = (form.localizations.keySet & activeCodes).toList.sorted,
              allT = form.localizations,
              error = None,
              csrf = "",
              localeNames = localeNames(locales, (form.localizations.keySet & activeCodes).toList.sorted),
              logo = None,
              client = None,
            ),
            version = form.version,
          )
          htmlResponse(solidPage(info, css, logo))
            // The page carries the user's sessions and passkeys; no cache may keep a copy.
            .addHeader(Header.Custom("Cache-Control", "private, no-cache, no-store"))

    private def etagFor(body: String): String =
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8))
      "\"" + Base64Url.encode(digest) + "\""

    override def renderSubmit(
        result: ConversationResult.Render,
        record: ConversationRecord,
        uiLocale: Option[String],
    ): Task[Response] =
      val challenge = Response.seeOther(UiLocales.withLocale(URL.root.copy(path = Path.root / "challenge"), uiLocale))
      result match
        case ConversationResult.RenderStep(step) =>
          ZIO.succeed(challenge)

        case ConversationResult.ServiceUnavailable =>
          renderStep(record, ifNoneMatch = None, errorKey = Some("service_unavailable"))

        case ConversationResult.BadRequest | ConversationResult.WriteConflict =>
          ZIO.succeed(challenge)

        case ConversationResult.Complete(redirectUri, state, code, sessionId, idTokenData, userAgentId, userAgentData) =>
          val encodedCode = Base64Url.encode(code)
          for
            sessionTtl <- configuration.getSessionTtl(record.clientId)
            userAgentTtl <- configuration.getUserAgentTtl(record.clientId)
            idToken <- idTokenData match
              case Some(data) =>
                for
                  client <- configuration.get(record.clientId)
                  signingKey <- jwksService.signingKey(client.tenantId)
                  cHash = JWT.leftHalfHash(encodedCode, signingKey.algorithm)
                  // FAPI 1.0 Advanced §5.2.2-4: `s_hash` binds the id_token to the `state`
                  // it was issued for, as `c_hash` binds it to the code.
                  sHash = state.map(state => "s_hash" -> Json.Str(JWT.leftHalfHash(state, signingKey.algorithm)))
                  dataWithHashes = data.copy(claims = data.claims ++ sHash + ("c_hash" -> Json.Str(cHash)))
                  token <- serializeIdToken(dataWithHashes, signingKey)
                yield Some(token)
              case None => ZIO.none
            redirectUrl <- responseService.redirect(
              record.clientId,
              redirectUri,
              record.responseMode,
              AuthorizeRedirect.successParams(encodedCode, state, idToken, config.jwt.issuer),
            )
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
        uiLocales: Option[List[String]],
        clientId: Option[ClientId],
    ): Task[Response] =
      val redirectUri = postLogoutRedirectUri
        .map(url => state.fold(url)(url.addQueryParam("state", _)).encode)
      for
        client <- clientId.fold(ZIO.succeed(None))(configuration.find)
        css <- themeCss(client.map(_.theme).getOrElse(ThemeDefault), client.flatMap(_.themeOverrides))
        logo <- tenantLogo(client)
        formOpt <- configuration.getForm("signed-out")
        locales <- configuration.getLocales
      yield formOpt match
        case None => htmlResponse(notFoundPage(css), Status.NotFound)
        case Some(form) =>
          val activeCodes = locales.locales.map(_.code).toSet + locales.default
          val (chosenLocale, translations) = pickTranslations(form, uiLocales, locales.default, activeCodes)
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
              localeNames = localeNames(locales, (form.localizations.keySet & activeCodes).toList.sorted),
              logo = None,
              client = None,
            ),
            version = form.version,
          )
          htmlResponse(solidPage(info, css, logo))
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
        css <- themeCss(client.map(_.theme).getOrElse(ThemeDefault), client.flatMap(_.themeOverrides))
        logo <- tenantLogo(client)
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
              localeNames = localeNames(locales, (value.localizations.keySet & activeCodes).toList.sorted),
              logo = None,
              client = None,
            ),
            value.version,
          )
          htmlResponse(logoutConfirmPage(info, css, logo))

    private def serializeIdToken(data: ConversationResult.IdTokenData, signingKey: JWT.Signature.Asymmetric): Task[String] =
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
        signature = signingKey,
      )

    private def formFor(
        step: ConversationStep,
        credential: Option[Either[Email, Phone]],
        clientId: ClientId,
        locale: Option[List[String]],
        redirectUri: URL,
        state: Option[State],
        csrfToken: String,
        availableClaims: Set[String],
        consentUser: Option[StepView.ConsentUser] = None,
        errorOverride: Option[String] = None,
        responseMode: ResponseMode = ResponseMode.Query,
    ): Task[Option[FormRenderInfo]] =
      val formId = step match
        case _: ConversationStep.Credential => "credential"
        case _: ConversationStep.Password => "password"
        case _: ConversationStep.SetPassword => "set-password"
        case _: ConversationStep.Otp => "otp"
        case _: ConversationStep.PasskeyEnroll => "passkey-enroll"
        case _: ConversationStep.Consent => "consent"
        case ConversationStep.AccessDenied => "access-denied"
      for
        view <- stepView(step, credential, clientId, locale, redirectUri, state, availableClaims, consentUser, responseMode)
        client <- configuration.find(clientId)
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
            client = clientBrand(client, locale, locales.default),
            localeNames = localeNames(locales, allLocales),
            logo = None,
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
      case s: ConversationStep.Consent if s.invalidGrant => Some("invalid_consent")
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
      // Language tags are case-insensitive (`pt-br` from a browser is `pt-BR` in the tenant's config).
      val chosenLocale = locale.getOrElse(Nil).iterator
        .flatMap(requested => activeCodes.find(_.equalsIgnoreCase(requested)))
        .find(form.localizations.contains)
        .getOrElse(default)
      val chosen = form.localizations.getOrElse(chosenLocale, Map.empty)
      (chosenLocale, base ++ chosen)

    // A theme is a whole stylesheet, except one built from tokens: that is the base stylesheet (a resource of util, not the
    // stored default theme, which is itself a token theme), what its
    // tokens set, then its own CSS. A base this service does not know (a newer central) gives the default look
    // rather than a broken page (plus the theme's own CSS).
    //
    // A client may carry overrides of its own (a partner app with its brand colour): they are one more rule after all
    // of the above, so they win over the theme. A stylesheet theme has no tokens to read the base from, so the default
    // preset stands in for it, and the overrides apply on top of the stylesheet; a stylesheet written against the legacy
    // names (--accent, --bg) that sets them to literals is not moved by --brand, as it would not be by any token.
    private def themeCss(themeId: String, overrides: Option[ThemeOverrides] = None): UIO[String] =
      themeBase(themeId).map: (css, resolved) =>
        val partial = (overrides.filterNot(_.isEmpty), resolved) match
          case (Some(own), Some(base)) => ThemeCss.generatePartial(base, own)
          case _ => ""
        Seq(css, partial).filter(_.nonEmpty).mkString("\n")

    private def themeBase(themeId: String): UIO[(String, Option[ResolvedTokens])] =
      val versola = ThemePresets.find(ThemePresets.DefaultId).map(_.tokens)
      configuration.getTheme(themeId).flatMap {
        case Some(theme) if theme.tokens.isDefined =>
          defaultCss.map: default =>
            theme.tokens.flatMap(ThemePresets.resolve(_).toOption) match
              case Some(resolved) => (ThemeCss.compose(default, resolved, theme.css), Some(resolved))
              case None => (Seq(default, theme.css).filter(_.nonEmpty).mkString("\n"), versola)
        case Some(theme) if theme.css.nonEmpty => ZIO.succeed((theme.css, versola))
        case _ => defaultCss.map((_, versola))
      }

    private def defaultCss: UIO[String] =
      ZIO.succeed(ThemeCss.baseStylesheet)

    /** Resolves one localized configuration string: the first requested locale that has a value (matched
      * case-insensitively, then without its region: `pt-BR` -> `pt`), then the tenant default, then any entry. The
      * same rule the forms apply to a client's names (see [[ClientBrand]]). `None` only when no entry has text, so
      * the form can render the raw token for a scope that has no description yet.
      */
    private def pickLocalized(
        values: Map[String, String],
        uiLocales: Option[List[String]],
        default: String,
    ): Option[String] =
      def lookup(code: String): Option[String] =
        values.collectFirst { case (key, value) if key.equalsIgnoreCase(code) && value.nonEmpty => value }
      def withoutRegion(code: String): Option[String] =
        Some(code.takeWhile(c => c != '-' && c != '_')).filter(_ != code)
      uiLocales.getOrElse(Nil).iterator
        .flatMap(requested => lookup(requested).orElse(withoutRegion(requested).flatMap(lookup)))
        .nextOption()
        .orElse(lookup(default))
        .orElse(values.toList.sortBy(_._1).collectFirst { case (_, value) if value.nonEmpty => value })

    private def stepView(
        step: ConversationStep,
        credential: Option[Either[Email, Phone]],
        clientId: ClientId,
        uiLocales: Option[List[String]],
        redirectUri: URL,
        state: Option[State],
        availableClaims: Set[String],
        consentUser: Option[StepView.ConsentUser],
        responseMode: ResponseMode,
    ): Task[StepView] =
      step match
        case ConversationStep.Credential(primaryCredentials, inlinePassword, passkey, registration, _, _, _) =>
          for
            phoneRegions <-
              if primaryCredentials.contains(PrimaryCredential.phone) then
                configuration.getPhoneRegions(clientId).map(Some(_))
              else
                ZIO.none
            passwordRegex <-
              if inlinePassword then configuration.getPasswordRegex.map(Some(_))
              else ZIO.none
          yield StepView.Credential(
            primaryCredentials,
            inlinePassword,
            passkey,
            phoneRegions,
            passwordRegex,
            registration,
          )

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
            // Masked here so the unmasked credential never reaches window.__VERSOLA_FORM__.
            destination = credential.map(_.fold(Email.mask, Phone.mask)),
          )

        case s: ConversationStep.PasskeyEnroll =>
          ZIO.succeed(StepView.PasskeyEnroll(publicKeyOptions = s.publicKeyOptions))

        case s: ConversationStep.Consent =>
          for
            client <- configuration.find(clientId)
            scopes <- configuration.getScopes
            locales <- configuration.getLocales
            denyUri <- returnUri(clientId, redirectUri.encode, state, "access_denied", responseMode)
            byToken = scopes.map(record => record.scope -> record).toMap
            mandatory = ConsentService.mandatoryScopes(client.flatMap(_.consentFlow))
            rows = s.requestedScope.toList.sortBy: token =>
              (if token.toString == "openid" then 0 else 1, token.toString)
            .map: token =>
              val record = byToken.get(token)
              val claims = record.toList
                .flatMap(_.claims.toList)
                .filter(claim => isAvailableClaim(claim.claim.toString, availableClaims))
                .flatMap: claim =>
                  pickLocalized(claim.description, uiLocales, locales.default).map: description =>
                    description -> claim.description
              val scopeDescriptionLocalizations = record.fold(Map.empty[String, String])(_.description)
              StepView.ConsentScope(
                scope = token,
                description = record.flatMap(r => pickLocalized(r.description, uiLocales, locales.default)),
                descriptionLocalizations = scopeDescriptionLocalizations,
                claims = claims.map(_._1),
                claimLocalizations = claims.map(_._2),
                deselectable = s.allowPartial && !mandatory.contains(token),
              )
          yield StepView.Consent(
            clientName = client.flatMap(c => pickLocalized(cleanNames(c.clientName), uiLocales, locales.default)),
            clientNames = client.fold(Map.empty[String, String])(c => cleanNames(c.clientName)),
            logoUri = client.flatMap(_.logoUri),
            policyUri = client.flatMap(_.policyUri),
            tosUri = client.flatMap(_.tosUri),
            scopes = rows,
            allowPartial = s.allowPartial,
            denyUri = denyUri.getOrElse(redirectUri.encode),
            user = consentUser,
          )

        case ConversationStep.AccessDenied =>
          returnUri(clientId, redirectUri.encode, state, "access_denied", responseMode)
            .map(uri => StepView.AccessDenied(redirectUri = uri.getOrElse(redirectUri.encode)))

    /** The client's name in the user's language and its logo, and whether it is a partner's app. */
    private def clientBrand(
        client: Option[OAuthClientRecord],
        uiLocales: Option[List[String]],
        default: String,
    ): Option[ClientBrand] =
      client.map: c =>
        val names = cleanNames(c.clientName)
        ClientBrand(
          name = pickLocalized(names, uiLocales, default),
          names = names,
          logo = c.logoUri,
          partner = c.consentFlow.isDefined,
        )

    private val MaxClientNameCodePoints = 100

    /** A client name is configuration, but it is drawn on a page the user trusts: control and format characters
      * (newlines, zero-width and bidi override characters, which could reorder the text around the name) are removed,
      * and it is cut by code points, so an emoji is never split into half a surrogate pair.
      */
    private def cleanName(name: String): String =
      val kept = name.codePoints().toArray.filter: cp =>
        val kind = Character.getType(cp)
        kind != Character.CONTROL && kind != Character.FORMAT && kind != Character.UNASSIGNED
      new String(kept, 0, math.min(kept.length, MaxClientNameCodePoints)).trim

    private def cleanNames(names: Map[String, String]): Map[String, String] =
      names.flatMap((code, name) => Some(cleanName(name)).filter(_.nonEmpty).map(code -> _))

    /** The language names an administrator gave the locales, for the languages this form is offered in. */
    private def localeNames(locales: Locales, offered: List[String]): Map[String, String] =
      locales.locales.collect { case locale if offered.contains(locale.code) && locale.name.nonEmpty => locale.code -> locale.name }.toMap

    /** Who is signed in, for the consent card: their name, a masked email or phone number, and their picture.
      * A name and a picture are the user's own `name` / `picture` claims, so they are bounded, and a picture
      * is used only when it is an https URL: it is loaded by the browser from whatever host the claim names.
      */
    private def consentUser(record: ConversationRecord): Option[StepView.ConsentUser] =
      def text(claim: String, limit: Int): Option[String] =
        record.userClaims.flatMap(_.get(claim)).collect { case Json.Str(value) if value.trim.nonEmpty => cutCodePoints(value.trim, limit) }
      val name = text("name", 200).orElse:
        Some(List(text("given_name", 100), text("family_name", 100)).flatten.mkString(" ")).filter(_.nonEmpty)
      // Not trimmed like the text claims: a URL with whitespace around it is refused, not repaired.
      val picture = record.userClaims.flatMap(_.get("picture")).collect { case Json.Str(value) if value.length <= 2048 => value }.filter(isHttpsUrl)
      val identifier = record.userEmail.map(Email.mask).orElse(record.userPhone.map(Phone.mask))
      Option.when(name.isDefined || identifier.isDefined)(StepView.ConsentUser(name, identifier, picture))

    private def cutCodePoints(value: String, limit: Int): String =
      if value.codePointCount(0, value.length) <= limit then value else value.substring(0, value.offsetByCodePoints(0, limit))

    /** Only an https URL with a host and no userinfo (`https://user:pw@host/` is how a link is made to look like
      * another host); the scheme is matched case-insensitively, as URLs do, and surrounding whitespace is rejected
      * rather than trimmed. A backslash makes the string no URI at all.
      */
    private def isHttpsUrl(value: String): Boolean =
      value == value.trim && scala.util.Try(new java.net.URI(value)).toOption.exists: uri =>
        Option(uri.getScheme).exists(_.equalsIgnoreCase("https")) && Option(uri.getHost).exists(_.nonEmpty) && uri.getRawUserInfo == null

    private def availableClaimNames(record: ConversationRecord): Set[String] =
      val customClaims = record.userClaims.toList
        .flatMap(_.fields)
        .collect { case (name, value) if hasUsableClaimValue(value) => name }
        .toSet
      customClaims ++
        record.userId.map(_ => "sub") ++
        record.userEmail.map(_ => "email") ++
        record.userPhone.map(_ => "phone_number")

    private def isAvailableClaim(claim: String, availableClaims: Set[String]): Boolean =
      availableClaims.contains(claim) || availableClaims.exists(_.startsWith(s"$claim#"))

    private def hasUsableClaimValue(value: Json): Boolean = value match
      case Json.Null => false
      case Json.Str(text) => text.nonEmpty
      case Json.Arr(values) => values.nonEmpty
      case Json.Obj(fields) => fields.nonEmpty
      case _ => true

    private def stepName(step: StepView): String = step match
      case _: StepView.Credential => "credential"
      case _: StepView.Password => "password"
      case _: StepView.SetPassword => "set-password"
      case _: StepView.Otp => "otp"
      case _: StepView.PasskeyEnroll => "passkey-enroll"
      case _: StepView.Consent => "consent"
      case _: StepView.AccountSettings => "auth-settings"
      case _: StepView.AccessDenied => "access-denied"
      case _: StepView.ConversationExpired => "conversation-expired"
      case _: StepView.ServiceUnavailable => "service-unavailable"
      case _: LogoutConfirm => "confirm-logout"
      case _: SignedOut => "signed-out"

    private def logoutConfirmPage(info: FormRenderInfo, themeCss: String, logo: Option[TenantLogo]): String =
      val config = inlineScriptJson(info.config.copy(logo = logo).toJson)
      s"""<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>${escapeHtml(
          info.title,
        )}</title>${faviconLink(
          logo,
        )}<style>${escapeCssForStyle(themeCss)} ${escapeCssForStyle(
          info.style,
        )}</style>$SchemeScript<script>window.__VERSOLA_FORM__ = $config;</script></head><body><div id="versola-form-root"></div><script>${info.jsCompiled.getOrElse(
          "",
        )}</script></body></html>"""

    /** The logo of the client's tenant. A page that belongs to no known client has no tenant to take one from, and
      * shows the Versola wordmark. */
    private def tenantLogo(client: Option[OAuthClientRecord]): UIO[Option[TenantLogo]] =
      client.fold(ZIO.none)(c => configuration.getTenantLogo(c.tenantId))

    /** The tenant's logo doubles as the favicon of its login pages: the file made for the browser's light or dark
      * chrome, which is the device's scheme, not the page's (the visitor's toggle does not reach a tab strip). */
    private def faviconLink(logo: Option[TenantLogo]): String =
      logo match
        case Some(TenantLogo(light, dark)) =>
          s"""<link rel="icon" href="${escapeAttribute(light)}" media="(prefers-color-scheme: light)">""" +
            s"""<link rel="icon" href="${escapeAttribute(dark)}" media="(prefers-color-scheme: dark)">"""
        case None => s"""<link rel="icon" href="$defaultFavicon">"""

    private val defaultFavicon =
      val svg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" fill="none"><rect width="64" height="64" rx="14" fill="#faf9f7"/><path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z" fill="#155e75" fill-opacity="0.06"/><path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z" fill="none" stroke="#155e75" stroke-width="2.2"/><text x="32" y="41" font-family="-apple-system, Inter, sans-serif" font-weight="800" font-size="26" fill="#155e75" text-anchor="middle">V</text></svg>"""
      "data:image/svg+xml;base64," + java.util.Base64.getEncoder.encodeToString(svg.getBytes(StandardCharsets.UTF_8))

    private def escapeAttribute(value: String): String =
      value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private def inlineScriptJson(value: String): String =
      value
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private def solidPage(info: FormRenderInfo, themeCss: String, logo: Option[TenantLogo]): String =
      val config = inlineScriptJson(info.config.copy(logo = logo).toJson)
      s"""<!DOCTYPE html>
         |<html lang="en">
         |  <head>
         |    <meta charset="UTF-8">
         |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
         |    <meta name="versola-step" content="${stepName(info.config.step)}">
         |    <title>${escapeHtml(info.title)}</title>
         |    ${faviconLink(logo)}
         |    <style>
         |      ${escapeCssForStyle(themeCss)}
         |      ${escapeCssForStyle(info.style)}
         |    </style>
         |    $SchemeScript
         |    <script>
         |      window.__VERSOLA_FORM__ = $config;
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
         |      ${escapeCssForStyle(themeCss)}
         |    </style>
         |    $SchemeScript
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
