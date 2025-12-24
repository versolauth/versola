package versola.oauth.conversation

import versola.auth.model.OtpCode
import versola.oauth.conversation.model.{AuthId, ConversationStep, Error}
import versola.oauth.model.ConversationCookie
import versola.util.http.Controller
import versola.util.{Email, FormDecoder, Phone}
import zio.*
import zio.http.*
import zio.http.codec.HttpContentCodec
import zio.http.datastar.DatastarEvent
import zio.http.template2.*
import zio.json.*
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ConversationController extends Controller:
  type Env = Tracing & ConversationRouter & ConversationRenderService

  def routes: Routes[Env, Nothing] = Routes(
    getFormRoute,
    submitEmailRoute,
    submitPhoneRoute,
    submitOtpRoute,
    submitResendOtpRoute,
  )

  val getFormRoute =
    Method.GET / "v1" / "challenge" -> handler { (request: Request) =>
      (
        for
          router <- ZIO.service[ConversationRouter]
          formService <- ZIO.service[ConversationRenderService]
          authId <- extractAuthId(request)
          record <- router.getConversation(authId).someOrFail(Error.BadRequest)
          html = formService.renderStep(record.step)
        yield Response.html(html)
      ).catchAll { ex =>
        ZIO.logErrorCause("Unknown exception", Cause.fail(ex)) *>
          ZIO.succeed(Response.internalServerError)
      }
    }

  val submitEmailRoute =
    submit[EmailSubmission](Method.POST / "v1" / "challenge" / "email")

  val submitPhoneRoute =
    submit[PhoneSubmission](Method.POST / "v1" / "challenge" / "phone")

  val submitOtpRoute =
    submit[OtpSubmission](Method.POST / "v1" / "challenge" / "otp")

  val submitResendOtpRoute =
    submit[OtpResendSubmission](Method.POST / "v1" / "challenge" / "otp" / "resend")

  private def submit[Body <: Submission: FormDecoder as decoder](
      pattern: RoutePattern[Unit],
  ): Route[ConversationRouter & ConversationRenderService, Nothing] =
    pattern -> handler { (request: Request) =>
      for
        router <- ZIO.service[ConversationRouter]
        conversationRenderService <- ZIO.service[ConversationRenderService]
        authId <- extractAuthId(request)
        form <- request.body.asURLEncodedForm
        body <- ZIO.fromEither(decoder.decode(form)).orElseFail(Error.BadRequest)
        submissionResult <- router.submit(authId, body)
        response = conversationRenderService.renderSubmit(submissionResult)
      yield response
    }.catchAll { err =>
      Handler.fromZIO(
        ZIO.logError(s"Error in submit: $err") *>
          ZIO.succeed(Response.status(Status.InternalServerError)),
      )
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
    Either.cond(form.formData.isEmpty, OtpResendSubmission(), "Form data is not empty")

  given FormDecoder[OtpSubmission] = (form: Form) =>
    FormDecoder.single[OtpCode](form, "code", code => Right(OtpCode(code)))
      .map(OtpSubmission(_))
