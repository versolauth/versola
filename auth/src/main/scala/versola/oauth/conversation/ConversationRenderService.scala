package versola.oauth.conversation

import versola.oauth.conversation.model.{ConversationStep, PrimaryCredential}
import versola.oauth.model.SessionCookie
import versola.util.{Base64Url, CoreConfig, Email, Phone}
import zio.http.datastar.*
import zio.http.template2.*
import zio.http.{Header, MediaType, Response, datastar}
import zio.{Chunk, ZLayer}

trait ConversationRenderService:
  def renderStep(step: ConversationStep): Dom.Element

  def renderSubmit(step: ConversationResult.Render): Response

object ConversationRenderService:
  val live = ZLayer.fromFunction(Impl(_))

  class Impl(config: CoreConfig) extends ConversationRenderService:
    override def renderStep(step: ConversationStep): Dom.Element =
      step match
        case ConversationStep.Empty(primaryCredential, passkey) =>
          entryForm(primaryCredential, passkey)

        case _: ConversationStep.Otp =>
          otpForm()

    override def renderSubmit(result: ConversationResult.Render): Response =
      result match
        case ConversationResult.RenderStep(step) =>
          val html = step match
            case ConversationStep.Empty(primaryCredential, passkey) =>
              entryFormBody(primaryCredential, passkey)

            case _: ConversationStep.Otp =>
              otpFormBody()

          eventToResponse(
            DatastarEvent.patchElements(html, Some(CssSelector.element("body")), ElementPatchMode.Inner)
          )

        case ConversationResult.NotFound =>
          Response.notFound

        case ConversationResult.LimitsExceeded =>
          Response.forbidden

        case ConversationResult.Complete(redirectUri, state, code, sessionId) =>
          val params = List("code" -> Base64Url.encode(code)) ++ state.map("state" -> _)
          val redirectUrl = redirectUri.addQueryParams(params)
          Response.seeOther(redirectUrl)
            .addCookie(SessionCookie(sessionId, config.security.ssoSession.ttl))

    private def maskCredential(credential: Either[Email, Phone]): String =
      credential match
        case Left(email) =>
          val parts = email.split("@")
          if parts.length == 2 then
            val local = parts(0)
            val domain = parts(1)
            val maskedLocal = if local.length > 2 then s"${local.take(2)}***" else "***"
            s"$maskedLocal@$domain"
          else "***@***"
        case Right(phone) =>
          if phone.length > 4 then s"***${phone.takeRight(4)}"
          else "***"

    private def entryForm(
        primaryCredential: PrimaryCredential,
        passkeySupported: Boolean,
    ): Dom.Element =
      html(
        head(
          meta(charset("UTF-8")),
          meta(name("viewport"), content("width=device-width, initial-scale=1.0")),
          title("Sign In - Versola Auth"),
          datastarScript,
          if primaryCredential == PrimaryCredential.Phone then
            script(src := "https://cdn.jsdelivr.net/npm/libphonenumber-js@1.11.11/bundle/libphonenumber-js.min.js")
          else
            Seq.empty
          ,
          style.inlineCss(css),
        ),
        body(
          entryFormBody(primaryCredential, passkeySupported)
        ),
      )

    private def entryFormBody(
        primaryCredential: PrimaryCredential,
        passkeySupported: Boolean,
    ): Dom =
      div(
        className := "container",
        primaryCredential match
          case PrimaryCredential.Email =>
            renderEmailForm(hasPasskey = passkeySupported)
          case PrimaryCredential.Phone =>
            renderPhoneForm(hasPasskey = passkeySupported)
      )

    private def renderEmailForm(hasPasskey: Boolean) =
      div(
        h1("Sign In"),
        form(
          id("credentialForm"),
          input(
            `type`("email"),
            name("email"),
            placeholder("Enter your email"),
            className := "input-field",
            required,
            pattern := "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            title("Please enter a valid email address"),
          ),
          button(
            `type`("button"),
            dataOn.click := Js("@post('/api/v1/challenge/email', {contentType: 'form'})"),
            className := "submit-button",
            "Continue",
          ),
          if hasPasskey then
            Seq(
              div(
                className := "divider",
                span("or"),
              ),
              button(
                `type`("button"),
                className := "passkey-button",
                "Sign in with Passkey",
              ),
            )
          else
            Seq.empty,
        ),
      )

    private def renderPhoneForm(hasPasskey: Boolean) =
      div(
        dataSignals := Js("{phoneError: ''}"),
        h1("Sign In"),
        form(
          id("credentialForm"),
          input(
            `type`("tel"),
            id("phoneInput"),
            name("phone"),
            placeholder("Enter your phone number (e.g., +1234567890)"),
            className := "input-field",
            required,
            dataOn.input := Js("$phoneError = ''"),
          ),
          div(
            id("phoneError"),
            className := "phone-error-message",
            dataShow := Js("$phoneError !== ''"),
            dataText := Js("$phoneError"),
          ),
          button(
            `type`("button"),
            id("phoneSubmitBtn"),
            className := "submit-button",
            dataOn.click := Js(
              """
              const input = document.getElementById('phoneInput');
              const value = input.value.trim();
              if (!value) { $phoneError = 'Phone number is required'; return; }
              try {
                const pn = libphonenumber.parsePhoneNumber(value);
                if (!pn || !pn.isValid()) { $phoneError = 'Please enter a valid phone number with country code'; return; }
                input.value = pn.number;
              } catch (e) { $phoneError = 'Please enter a valid phone number with country code'; return; }
              @post('/api/v1/challenge/phone', {contentType: 'form'})
              """,
            ),
            "Continue",
          ),
          if hasPasskey then
            Seq(
              div(
                className := "divider",
                span("or"),
              ),
              button(
                `type`("button"),
                className := "passkey-button",
                "Sign in with Passkey",
              ),
            )
          else
            Seq.empty,
        ),
      )

    private def otpForm(): Dom.Element =
      html(
        head(
          meta(charset("UTF-8")),
          meta(name("viewport"), content("width=device-width, initial-scale=1.0")),
          title("Enter Code - Versola Auth"),
          datastarScript,
          style.inlineCss(css),
        ),
        body(
          div(
            className := "container",
            renderOtpForm(),
          ),
        ),
      )

    private def otpFormBody(): Dom =
      div(
        className := "container",
        renderOtpForm(),
      )

    private def renderOtpForm() =
      div(
        h1("Enter Code"),
        p(
          className := "otp-description",
          s"We sent a verification code to entered credential",
        ),
        form(
          id("otpForm"),
          input(
            `type`("text"),
            name("code"),
            placeholder("Enter verification code"),
            className := "input-field otp-input",
            required,
            autocomplete := "one-time-code",
            Dom.attr("inputmode", "numeric"),
            pattern := "[0-9]*",
            maxlength := "6",
          ),
          button(
            `type`("button"),
            dataOn.click := Js("@post('/api/v1/challenge/otp', {contentType: 'form'})"),
            className := "submit-button",
            "Verify",
          ),
          div(
            className := "divider",
            span("or"),
          ),
          button(
            `type`("button"),
            dataOn.click := Js("@post('/api/v1/challenge/otp/resend', {contentType: 'form'})"),
            className := "resend-button",
            "Resend Code",
          ),
        ),
      )


    private def eventToResponse(event: DatastarEvent) =
      datastar.datastarEventCodec.encodeResponse(
        event,
        Chunk(
          Header.Accept.MediaTypeWithQFactor(MediaType.text.`html`, None),
          Header.Accept.MediaTypeWithQFactor(MediaType.application.`json`, None),
          Header.Accept.MediaTypeWithQFactor(MediaType.text.`javascript`, None),
        ),
        zio.http.codec.CodecConfig.defaultConfig,
      )

private val css =
  css"""
  * {
    box-sizing: border-box;
  }
  html {
    height: 100%;
  }
  body {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 100%;
    font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    font-size: 16px;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    margin: 0;
    padding: 20px;
  }
  .container {
    text-align: center;
    background: white;
    border-radius: 12px;
    padding: 40px;
    box-shadow: 0 10px 40px rgba(0,0,0,0.2);
    max-width: 420px;
    width: 100%;
  }
  h1 {
    font-size: 2rem;
    font-weight: 700;
    color: #1a1a1a;
    margin-bottom: 30px;
    margin-top: 0;
  }
  form {
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin-bottom: 20px;
  }
  .input-field {
    padding: 14px 16px;
    font-size: 1rem;
    border: 2px solid #e0e0e0;
    border-radius: 8px;
    outline: none;
    transition: border-color 0.2s, box-shadow 0.2s;
    width: 100%;
  }
  .input-field:focus {
    border-color: #667eea;
    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
  }
  .input-field:invalid:not(:placeholder-shown) {
    border-color: #ef4444;
  }
  .submit-button {
    padding: 14px 24px;
    font-size: 1rem;
    font-weight: 600;
    color: white;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border: none;
    border-radius: 8px;
    cursor: pointer;
    transition: transform 0.2s, box-shadow 0.2s;
  }
  .submit-button:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
  }
  .submit-button:active {
    transform: translateY(0);
  }
  .submit-button:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    transform: none;
  }
  .divider {
    display: flex;
    align-items: center;
    text-align: center;
    margin: 8px 0;
    color: #666;
    font-size: 0.875rem;
  }
  .divider::before,
  .divider::after {
    content: '';
    flex: 1;
    border-bottom: 1px solid #e0e0e0;
  }
  .divider span {
    padding: 0 12px;
  }
  .passkey-button {
    padding: 14px 24px;
    font-size: 1rem;
    font-weight: 600;
    color: #667eea;
    background: white;
    border: 2px solid #667eea;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .passkey-button:hover {
    background: #f0f4ff;
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
  }
  .passkey-button:active {
    transform: translateY(0);
  }
  .error-message {
    font-size: 1rem;
    color: #666;
    line-height: 1.6;
    margin: 20px 0;
  }
  .phone-error-message {
    margin-top: 8px;
    font-size: 14px;
    color: #dc2626;
    text-align: left;
  }
  #message {
    font-size: 1rem;
    margin-top: 20px;
    padding: 16px;
    background: #f0f4ff;
    border-left: 4px solid #667eea;
    border-radius: 6px;
    color: #333;
    min-height: 50px;
    display: flex;
    align-items: center;
    justify-content: center;
    text-align: left;
  }
  .otp-description {
    font-size: 1rem;
    color: #666;
    margin-bottom: 24px;
    line-height: 1.5;
  }
  .otp-input {
    text-align: center;
    font-size: 1.5rem;
    letter-spacing: 0.5em;
    font-weight: 600;
  }
  .resend-button {
    padding: 14px 24px;
    font-size: 1rem;
    font-weight: 600;
    color: #667eea;
    background: white;
    border: 2px solid #667eea;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .resend-button:hover {
    background: #f0f4ff;
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
  }
  .resend-button:active {
    transform: translateY(0);
  }
  @media (max-width: 480px) {
    .container {
      padding: 30px 20px;
    }
    h1 {
      font-size: 1.75rem;
    }
  }
  """
