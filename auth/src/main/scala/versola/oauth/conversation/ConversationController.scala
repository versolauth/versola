package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.Error
import versola.oauth.model.ConversationCookie
import versola.user.model.Login
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, Email, FormDecoder, Phone}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ConversationController extends Controller:
  type Env = Tracing & ConversationRouter & ConversationRenderService & CoreConfig & OAuthConfigurationService

  def routes: Routes[Env, Throwable] = Routes(
    getFormRoute,
    submitEmailRoute,
    submitPhoneRoute,
    submitPasswordRoute,
    submitLoginPasswordRoute,
    submitOtpRoute,
    submitResendOtpRoute,
    getPasskeyOptionsRoute,
    submitPasskeyAssertionRoute,
    submitPasskeyEnrollRoute,
    submitPasskeySkipRoute,
    submitSetPasswordRoute,
    submitConsentRoute,
    submitConsentDenyRoute,
  )

  val getFormRoute =
    Method.GET / "challenge" -> handler { (request: Request) =>
      (
        for
          router <- ZIO.service[ConversationRouter]
          formService <- ZIO.service[ConversationRenderService]
          cookie <- extractCookie(request)
          _ <- Observability.setAuth(cookie.authId.toString, cookie.clientId)
          record <- router.getConversation(cookie.authId)
          response <- record match
            case None => formService.renderExpired(cookie.clientId, cookie.redirectUri, cookie.state)
            case Some(record) =>
              val ifNoneMatch = request.headers.get(Header.IfNoneMatch)
              formService.renderStep(record, ifNoneMatch.map(_.renderedValue))
        yield response
      ).catchAll {
        case Error.ConversationExpired => expiredResponse(request)
        case Error.ServiceUnavailable => serviceUnavailableResponse(request)
        case _: Error => ZIO.succeed(Response.badRequest)
        case ex: Throwable => ZIO.fail(ex)
      }
    }

  val submitEmailRoute =
    submit[EmailSubmission](Method.POST / "challenge" / "email")

  val submitPhoneRoute =
    submit[PhoneSubmission](Method.POST / "challenge" / "phone")

  val submitPasswordRoute =
    submit[PasswordSubmission](Method.POST / "challenge" / "password")

  val submitLoginPasswordRoute =
    submit[LoginPasswordSubmission](Method.POST / "challenge" / "login-password")

  val submitOtpRoute =
    submit[OtpSubmission](Method.POST / "challenge" / "otp")

  val submitResendOtpRoute =
    submit[OtpResendSubmission](Method.POST / "challenge" / "otp" / "resend")

  /** GET /challenge/passkey/options — starts a discoverable assertion and returns the
    * PublicKeyCredentialRequestOptions JSON for `navigator.credentials.get()`.
    *
    * This route is only ever called via `fetch` by the credential form's JS, never as a
    * top-level navigation. On expiry/unavailability we must not return the HTML terminal
    * page with a 200 status — the caller would try to JSON-parse it as the options payload.
    * Instead we signal these conditions with distinct non-2xx statuses so the client can
    * navigate to `/challenge` itself and let the server render the proper terminal screen.
    */
  val getPasskeyOptionsRoute =
    Method.GET / "challenge" / "passkey" / "options" -> handler { (request: Request) =>
      (for
        router  <- ZIO.service[ConversationRouter]
        cookie  <- extractCookie(request)
        _       <- Observability.setAuth(cookie.authId.toString, cookie.clientId)
        options <- router.startPasskeyOptions(cookie.authId).someOrFail(Error.BadRequest)
      yield Response.json(options),
      ).catchAll {
        case Error.ConversationExpired => ZIO.succeed(Response.status(Status.Gone))
        case Error.ServiceUnavailable => ZIO.succeed(Response.status(Status.InternalServerError))
        case _: Error => ZIO.succeed(Response.badRequest)
        case ex: Throwable => ZIO.fail(ex)
      }
    }

  val submitPasskeyAssertionRoute =
    submit[PasskeyAssertionSubmission](Method.POST / "challenge" / "passkey")

  val submitPasskeyEnrollRoute =
    submit[PasskeyEnrollSubmission](Method.POST / "challenge" / "passkey" / "enroll")

  val submitPasskeySkipRoute =
    submit[PasskeySkipSubmission](Method.POST / "challenge" / "passkey" / "skip")

  val submitSetPasswordRoute =
    submit[SetPasswordSubmission](Method.POST / "challenge" / "set-password")

  val submitConsentRoute =
    submit[ConsentAllowSubmission](Method.POST / "challenge" / "consent")

  val submitConsentDenyRoute =
    submit[ConsentDenySubmission](Method.POST / "challenge" / "consent" / "deny")

  private def submit[Body <: Submission: FormDecoder](
      pattern: RoutePattern[Unit],
  ): Route[ConversationRouter & ConversationRenderService & CoreConfig & OAuthConfigurationService, Throwable] =
    pattern -> handler { (request: Request) =>
      (for
        router <- ZIO.service[ConversationRouter]
        conversationRenderService <- ZIO.service[ConversationRenderService]
        cookie <- extractCookie(request)
        _ <- Observability.setAuth(cookie.authId.toString, cookie.clientId)
        ipHeader <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getIpHeader(cookie.clientId))
        ip = extractIp(request, ipHeader)
        _ <- ZIO.foreachDiscard(ip)(Observability.setIp)

        body <- request.formAs[Body]
          .tapError(msg => ZIO.logWarning(s"Couldn't parse request body: $msg"))
          .orElseFail(Error.BadRequest)

        _ <- ZIO.fail(Error.BadRequest)
          .unlessZIO(validate(cookie.clientId, body))
          .tapError(msg => ZIO.logWarning(s"Request validation failed: $msg"))

        uiLocale <- request.queryZIO[Option[String]]("ui_locale")
        (result, record) <- router.submit(cookie.authId, body, uiLocale, ip)
        response <- conversationRenderService.renderSubmit(result, record)
      yield response)
        .catchAll {
          case Error.ConversationExpired => expiredResponse(request)
          case Error.ServiceUnavailable => serviceUnavailableResponse(request)
          case _: Error => ZIO.succeed(Response.badRequest)
          case ex: Throwable => ZIO.fail(ex)
        }
    }

  /** Validate submission payloads against tenant config using the trusted clientId from the signed
   *  cookie. Runs against the in-memory cache — no DB call required.
   */
  private def validate(clientId: ClientId, submission: Submission): URIO[OAuthConfigurationService, Boolean] =
    submission match
      case submitted: PhoneSubmission =>
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getAllowedPhonePrefixes(clientId))
          .map(prefixes => prefixes.isEmpty || prefixes.exists(submitted.phone.startsWith))

      case submitted: PasswordSubmission =>
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasswordRegex)
          .map(regex => scala.util.Try(submitted.password.matches(regex)).getOrElse(true))

      case submitted: LoginPasswordSubmission =>
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasswordRegex)
          .map(regex => scala.util.Try(submitted.password.matches(regex)).getOrElse(true))

      case submitted: SetPasswordSubmission =>
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasswordRegex)
          .map(regex => scala.util.Try(submitted.password.matches(regex)).getOrElse(true))

      case _ =>
        ZIO.succeed(true)

  private def extractCookie(
      request: Request,
  ): ZIO[CoreConfig, Error, ConversationCookie] =
    ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret).flatMap: secret =>
      request.cookie(ConversationCookie.name) match
        case Some(cookie) =>
          ZIO.fromEither(ConversationCookie.parse(cookie.content, secret))
            .tapError(msg => ZIO.logWarning(s"Couldn't parse conversation cookie: $msg"))
            .orElseFail(Error.BadRequest)
        case None =>
          ZIO.fail(Error.BadRequest)
            .tapError(msg => ZIO.logWarning(s"Conversation cookie is missing"))

  private def expiredResponse(request: Request): ZIO[ConversationRenderService & CoreConfig, Throwable, Response] =
    for
      formService <- ZIO.service[ConversationRenderService]
      cookie <- extractCookie(request)
      response <- formService.renderExpired(cookie.clientId, cookie.redirectUri, cookie.state)
    yield response

  private def serviceUnavailableResponse(request: Request): ZIO[ConversationRenderService & CoreConfig, Throwable, Response] =
    for
      formService <- ZIO.service[ConversationRenderService]
      cookie <- extractCookie(request)
      response <- formService.renderServiceUnavailable(cookie.clientId, cookie.redirectUri, cookie.state)
    yield response

  /** Extracts the client IP from the header configured in submission limits. Returns None when no
    * header is configured, causing IP-based throttling to be skipped entirely. For multi-value
    * headers such as X-Forwarded-For only the first (leftmost) value is used.
    */
  private def extractIp(request: Request, ipHeader: String): Option[String] =
    request.headers.get(ipHeader).map(_.split(',').head.trim).filter(_.nonEmpty)

  given FormDecoder[PhoneSubmission] = (form: Form) =>
    for
      phone <- FormDecoder.single[Phone](form, "phone", Phone.parse)
      csrf  <- FormDecoder.single[String](form, "csrf", Right(_))
    yield PhoneSubmission(phone, csrf)

  given FormDecoder[EmailSubmission] = (form: Form) =>
    for
      email <- FormDecoder.single[Email](form, "email", Email.from)
      csrf  <- FormDecoder.single[String](form, "csrf", Right(_))
    yield EmailSubmission(email, csrf)

  given FormDecoder[OtpResendSubmission] = (form: Form) =>
    FormDecoder.single[String](form, "csrf", Right(_)).map(OtpResendSubmission(_))

  given FormDecoder[OtpSubmission] = (form: Form) =>
    for
      code <- FormDecoder.single[OtpCode](form, "code", code => Right(OtpCode(code)))
      csrf <- FormDecoder.single[String](form, "csrf", Right(_))
    yield OtpSubmission(code, csrf)

  given FormDecoder[PasswordSubmission] = (form: Form) =>
    for
      password <- FormDecoder.single[String](form, "password", Right(_))
      csrf     <- FormDecoder.single[String](form, "csrf", Right(_))
    yield PasswordSubmission(Password(password), csrf)

  given FormDecoder[LoginPasswordSubmission] = (form: Form) =>
    for
      login    <- FormDecoder.single[String](form, "login", Right(_))
      password <- FormDecoder.single[String](form, "password", Right(_))
      csrf     <- FormDecoder.single[String](form, "csrf", Right(_))
    yield LoginPasswordSubmission(Login(login), Password(password), csrf)

  given FormDecoder[PasskeyAssertionSubmission] = (form: Form) =>
    for
      response <- FormDecoder.single[String](form, "response", Right(_))
      csrf     <- FormDecoder.single[String](form, "csrf", Right(_))
    yield PasskeyAssertionSubmission(response, csrf)

  private val PasskeyNameRegex = "^[\\p{L}\\p{N} ()-]+$"

  given FormDecoder[PasskeyEnrollSubmission] = (form: Form) =>
    for
      response <- FormDecoder.single[String](form, "response", Right(_))
      name     <- FormDecoder.single[String](form, "name", n =>
        if n == n.trim && n.nonEmpty && n.matches(PasskeyNameRegex) then Right(n)
        else Left("Invalid passkey name: only letters, digits, spaces, hyphens, and parentheses are allowed, with no leading or trailing spaces"),
      )
      csrf     <- FormDecoder.single[String](form, "csrf", Right(_))
    yield PasskeyEnrollSubmission(response, name, csrf)

  given FormDecoder[PasskeySkipSubmission] = (form: Form) =>
    FormDecoder.single[String](form, "csrf", Right(_)).map(PasskeySkipSubmission(_))

  given FormDecoder[SetPasswordSubmission] = (form: Form) =>
    for
      password <- FormDecoder.single[String](form, "password", Right(_))
      csrf     <- FormDecoder.single[String](form, "csrf", Right(_))
    yield SetPasswordSubmission(Password(password), csrf)

  given FormDecoder[ConsentAllowSubmission] = (form: Form) =>
    for
      // A space-delimited list, matching how scope travels everywhere else in OAuth. Empty is
      // legitimate: the client may have requested only optional scopes and the user deselected
      // all of them.
      scope <- FormDecoder.optional[Set[ScopeToken]](form, "scope", value => Right(ScopeToken.parseTokens(value).filter(_.nonEmpty)))
      csrf  <- FormDecoder.single[String](form, "csrf", Right(_))
    yield ConsentAllowSubmission(scope.getOrElse(Set.empty), csrf)

  given FormDecoder[ConsentDenySubmission] = (form: Form) =>
    FormDecoder.single[String](form, "csrf", Right(_)).map(ConsentDenySubmission(_))
