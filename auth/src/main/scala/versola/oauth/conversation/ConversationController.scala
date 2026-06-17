package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.oauth.model.ConversationCookie
import versola.user.model.Login
import versola.util.http.Controller
import versola.util.{Email, FormDecoder, Phone}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ConversationController extends Controller:
  type Env = Tracing & ConversationRouter & ConversationRenderService & OAuthConfigurationService

  def routes: Routes[Env, Throwable] = Routes(
    getFormRoute,
    submitEmailRoute,
    submitPhoneRoute,
    submitPasswordRoute,
    submitOtpRoute,
    submitResendOtpRoute,
  )

  val getFormRoute =
    Method.GET / "challenge" -> handler { (request: Request) =>
      (
        for
          router <- ZIO.service[ConversationRouter]
          formService <- ZIO.service[ConversationRenderService]
          authId <- extractAuthId(request)
          record <- router.getConversation(authId).someOrFail(Error.BadRequest)
          ifNoneMatch = request.headers.get(Header.IfNoneMatch)
          response <- formService.renderStep(record, ifNoneMatch.map(_.renderedValue))
        yield response
      ).catchAll {
        case error: Error => ZIO.succeed(Response.badRequest)
        case ex: Throwable => ZIO.fail(ex)
      }
    }

  val submitEmailRoute =
    submit[EmailSubmission](Method.POST / "challenge" / "email")

  val submitPhoneRoute =
    submit[PhoneSubmission](
      Method.POST / "challenge" / "phone",
      validate = (record, body) =>
        ZIO.serviceWithZIO[OAuthConfigurationService]: svc =>
          svc.getAllowedPhonePrefixes(record.clientId).flatMap: prefixes =>
            ZIO.fail(Error.BadRequest)
              .unless(prefixes.isEmpty || prefixes.exists(body.phone.startsWith))
              .unit,
    )

  val submitPasswordRoute =
    submit[PasswordSubmission](Method.POST / "challenge" / "password")

  val submitLoginPasswordRoute =
    submit[LoginPasswordSubmission](Method.POST / "challenge" / "login-password")

  val submitOtpRoute =
    submit[OtpSubmission](Method.POST / "challenge" / "otp")

  val submitResendOtpRoute =
    submit[OtpResendSubmission](Method.POST / "challenge" / "otp" / "resend")

  private def submit[Body <: Submission: FormDecoder](
      pattern: RoutePattern[Unit],
      validate: (ConversationRecord, Body) => ZIO[OAuthConfigurationService, Error, Unit] = (r: ConversationRecord, b: Body) => ZIO.unit,
  ): Route[ConversationRouter & ConversationRenderService & OAuthConfigurationService, Throwable] =
    pattern -> handler { (request: Request) =>
      (for
        router <- ZIO.service[ConversationRouter]
        conversationRenderService <- ZIO.service[ConversationRenderService]
        authId <- extractAuthId(request)
        record <- router.getConversation(authId).someOrFail(Error.BadRequest)
        body <- request.formAs[Body].orElseFail(Error.BadRequest)
        _ <- validate(record, body)
        submissionResult <- router.submit(authId, body)
        response <- conversationRenderService.renderSubmit(submissionResult, record.clientId, record.uiLocales)
      yield response)
        .catchAll {
          case error: Error => ZIO.succeed(Response.badRequest)
          case ex: Throwable => ZIO.fail(ex)
        }
    }

  private def extractAuthId(
      request: Request,
  ): IO[Error, AuthId] =
    request.cookie(ConversationCookie.name) match
      case Some(cookie) =>
        ZIO.fromEither(AuthId.parse(cookie.content).left.map(_ => Error.BadRequest))
      case None =>
        ZIO.fail(Error.BadRequest)

  given FormDecoder[PhoneSubmission] = (form: Form) =>
    FormDecoder.single[Phone](form, "phone", Phone.parse)
      .map(PhoneSubmission(_))

  given FormDecoder[EmailSubmission] = (form: Form) =>
    FormDecoder.single[Email](form, "email", Email.from)
      .map(EmailSubmission(_))

  given FormDecoder[OtpResendSubmission] = (form: Form) =>
    ZIO.succeed(OtpResendSubmission())
      .when(form.formData.isEmpty)
      .someOrFail("Form data is not empty")

  given FormDecoder[OtpSubmission] = (form: Form) =>
    FormDecoder.single[OtpCode](form, "code", code => Right(OtpCode(code)))
      .map(OtpSubmission(_))

  given FormDecoder[PasswordSubmission] = (form: Form) =>
    FormDecoder.single[String](form, "password", Right(_))
      .map(p => PasswordSubmission(Password(p)))

  given FormDecoder[LoginPasswordSubmission] = (form: Form) =>
    for
      login <- FormDecoder.single[String](form, "login", Right(_))
      password <- FormDecoder.single[String](form, "password", Right(_))
    yield LoginPasswordSubmission(Login(login), Password(password))

  // TODO login/password validation
