package versola.oauth.conversation

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.http.{ControllerSpec, NoopTracing}
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.forms.ConversationRenderService
import versola.oauth.model.ConversationCookie
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.http.*
import zio.internal.stacktracer.SourceLocation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object ConversationControllerSpec extends UnitSpecBase:
  type Service = ConversationRouter

  val authId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val email = Email("test@example.com")
  val phone = Phone("+12025551234")
  val otpCode = OtpCode("123456")
  val conversationCookie = Header.Cookie(
    NonEmptyChunk(Cookie.Request(ConversationCookie.name, authId.toString))
  )

  val conversationResult = ConversationResult.RenderStep(
    ConversationStep.Otp(
      real = Some(ConversationStep.Otp.Real(otpCode)),
      timesRequested = 1,
      timesSubmitted = 0,
    ),
  )

  def successfulSubmitTestCase[Args, Result, RResult <: Result](
      description: String,
      request: Request,
      submission: (AuthId, Submission),
  )(using
      loc: SourceLocation,
      trace: Trace,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        formService <- ConversationRenderService.live
          .build
          .provideSome[zio.Scope](ZLayer.succeed(TestEnvConfig.coreConfig))

        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          ConversationController.routes
            .provideEnvironment(ZEnvironment(router) ++ formService ++ tracing)
        )
        _ <- router.submit.succeedsWith(conversationResult)

        response <- client.batched(request)

        submitCalls = router.submit.calls
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get("datastar-selector").contains("body"),
        response.headers.get("datastar-mode").contains("inner"),
      ) && assertTrue(submitCalls == List(submission))
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("ConversationController")(
    successfulSubmitTestCase(
      description = "submit email",
      request = Request.post(
        url = URL.empty / "v1" / "challenge" / "email",
        body = Body.fromURLEncodedForm(
          Form(FormField.Text("email", email, MediaType.text.plain)),
        )
      ).addHeader(conversationCookie),
      submission = (authId, EmailSubmission(email)),
    ),
    successfulSubmitTestCase(
      description = "submit phone",
      request = Request.post(
        url = URL.empty / "v1" / "challenge" / "phone",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("phone" -> phone),
        )
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone)),
    ),
    successfulSubmitTestCase(
      description = "submit otp",
      request = Request.post(
        url = URL.empty / "v1" / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode)),
    ),
    successfulSubmitTestCase(
      description = "submit otp resend",
      request = Request.post(
        url = URL.empty / "v1" / "challenge" / "otp" / "resend",
        body = Body.fromURLEncodedForm(
          Form.fromStrings(),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpResendSubmission()),
    ),
  )
