package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.Error
import versola.oauth.model.ConversationCookie
import versola.user.model.Login
import versola.util.http.Controller
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
  )

  val getFormRoute =
    Method.GET / "challenge" -> handler { (request: Request) =>
      (
        for
          router <- ZIO.service[ConversationRouter]
          formService <- ZIO.service[ConversationRenderService]
          cookie <- extractCookie(request)
          record <- router.getConversation(cookie.authId).someOrFail(Error.BadRequest)
          ifNoneMatch = request.headers.get(Header.IfNoneMatch)
          response <- formService.renderStep(record, ifNoneMatch.map(_.renderedValue))
        yield response
      ).catchAll {
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
    */
  val getPasskeyOptionsRoute =
    Method.GET / "challenge" / "passkey" / "options" -> handler { (request: Request) =>
      (for
        router  <- ZIO.service[ConversationRouter]
        cookie  <- extractCookie(request)
        options <- router.startPasskeyOptions(cookie.authId).someOrFail(Error.BadRequest)
      yield Response.json(options),
      ).catchAll {
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

  private def submit[Body <: Submission: FormDecoder](
      pattern: RoutePattern[Unit],
  ): Route[ConversationRouter & ConversationRenderService & CoreConfig & OAuthConfigurationService, Throwable] =
    pattern -> handler { (request: Request) =>
      (for
        router <- ZIO.service[ConversationRouter]
        conversationRenderService <- ZIO.service[ConversationRenderService]
        cookie <- extractCookie(request)
        body <- request.formAs[Body].orElseFail(Error.BadRequest)
        _ <- ZIO.fail(Error.BadRequest).unlessZIO(validate(cookie.clientId, body))
        uiLocale <- request.queryZIO[Option[String]]("ui_locale")
        ipHeader <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getIpHeader(cookie.clientId))
        (result, record) <- router.submit(cookie.authId, body, uiLocale, extractIp(request, ipHeader))
        response <- conversationRenderService.renderSubmit(result, record)
      yield response)
        .catchAll {
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
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasswordRegex(clientId))
          .map(_.forall(regex => scala.util.Try(submitted.password.matches(regex)).getOrElse(true)))

      case submitted: LoginPasswordSubmission =>
        ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasswordRegex(clientId))
          .map(_.forall(regex => scala.util.Try(submitted.password.matches(regex)).getOrElse(true)))

      case _ =>
        ZIO.succeed(true)

  private def extractCookie(
      request: Request,
  ): ZIO[CoreConfig, Error, ConversationCookie] =
    ZIO.serviceWith[CoreConfig](_.security.conversationCookieSecret).flatMap: secret =>
      request.cookie(ConversationCookie.name) match
        case Some(cookie) =>
          ZIO.fromEither(ConversationCookie.parse(cookie.content, secret).left.map(_ => Error.BadRequest))
        case None =>
          ZIO.fail(Error.BadRequest)

  /** Extracts the client IP from the header configured in submission limits. Returns None when no
    * header is configured, causing IP-based throttling to be skipped entirely. For multi-value
    * headers such as X-Forwarded-For only the first (leftmost) value is used.
    */
  private def extractIp(request: Request, ipHeader: String): Option[String] =
    request.headers.get(ipHeader).map(_.split(',').head.trim).filter(_.nonEmpty)

  given FormDecoder[PhoneSubmission] = (form: Form) =>
    FormDecoder.single[Phone](form, "phone", Phone.parse)
      .map(PhoneSubmission(_))

  given FormDecoder[EmailSubmission] = (form: Form) =>
    FormDecoder.single[Email](form, "email", Email.from)
      .map(EmailSubmission(_))

  given FormDecoder[OtpResendSubmission] = (_: Form) =>
    ZIO.succeed(OtpResendSubmission())

  given FormDecoder[OtpSubmission] = (form: Form) =>
    FormDecoder.single[OtpCode](form, "code", code => Right(OtpCode(code)))
      .map(OtpSubmission(_))

  given FormDecoder[PasswordSubmission] = (form: Form) =>
    FormDecoder.single[String](form, "password", Right(_))
      .map(p => PasswordSubmission(Password(p)))

  given FormDecoder[LoginPasswordSubmission] = (form: Form) =>
    for
      login    <- FormDecoder.single[String](form, "login", Right(_))
      password <- FormDecoder.single[String](form, "password", Right(_))
    yield LoginPasswordSubmission(Login(login), Password(password))

  given FormDecoder[PasskeyAssertionSubmission] = (form: Form) =>
    FormDecoder.single[String](form, "response", Right(_))
      .map(PasskeyAssertionSubmission(_))

  given FormDecoder[PasskeyEnrollSubmission] = (form: Form) =>
    for
      response <- FormDecoder.single[String](form, "response", Right(_))
      name     <- FormDecoder.optional[String](form, "name", Right(_))
    yield PasskeyEnrollSubmission(response, name)

  given FormDecoder[PasskeySkipSubmission] = (_: Form) =>
    ZIO.succeed(PasskeySkipSubmission())
