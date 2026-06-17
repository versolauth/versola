package versola.oauth.conversation

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, FormRecord, PrimaryCredential}
import versola.oauth.conversation.model.{ConversationRecord, ConversationStep}
import versola.oauth.model.SessionCookie
import versola.util.{Base64Url, CoreConfig, JWT}
import zio.http.{Body, Header, Headers, MediaType, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.json.{jsonDiscriminator, jsonHint}
import zio.{Chunk, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

trait ConversationRenderService:
  def renderStep(record: ConversationRecord, ifNoneMatch: Option[String]): Task[Response]

  def renderSubmit(
      result: ConversationResult.Render,
      clientId: versola.oauth.client.model.ClientId,
      locale: Option[List[String]],
  ): Task[Response]

object ConversationRenderService:
  val live = ZLayer.fromFunction(Impl(_, _))

  @jsonDiscriminator("type")
  sealed trait StepView derives JsonCodec

  object StepView:
    @jsonHint("credential")
    case class Credential(
        primaryCredentials: List[PrimaryCredential],
        inlinePassword: Boolean,
        passkey: Boolean,
        allowedPhonePrefixes: Option[List[String]],
    ) extends StepView
    @jsonHint("password")
    case object Password extends StepView
    @jsonHint("otp")
    case class Otp(length: Int, resendAfter: Int) extends StepView

  case class FormConfig(
      step: StepView,
      t: Map[String, String],
      locale: String,
      locales: List[String],
      allT: Map[String, Map[String, String]],
  ) derives JsonCodec

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
  ) extends ConversationRenderService:
    override def renderStep(record: ConversationRecord, ifNoneMatch: Option[String]): Task[Response] =
      for
        client <- configuration.find(record.clientId)
        themeId = client.map(_.theme).getOrElse(ThemeDefault)
        css <- themeCss(themeId)
        maybeInfo <- formFor(record.step, record.clientId, record.uiLocales)
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

    private def etagFor(body: String): String =
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8))
      "\"" + Base64Url.encode(digest) + "\""

    override def renderSubmit(
        result: ConversationResult.Render,
        clientId: versola.oauth.client.model.ClientId,
        locale: Option[List[String]],
    ): Task[Response] =
      result match
        case ConversationResult.RenderStep(step) =>
          ZIO.succeed(Response.seeOther(URL.root.copy(path = zio.http.Path.root / "challenge")))

        case ConversationResult.NotFound =>
          ZIO.succeed(Response.notFound)

        case ConversationResult.LimitsExceeded =>
          ZIO.succeed(Response.forbidden)

        case ConversationResult.IllegalState =>
          ZIO.succeed(Response.badRequest)

        case ConversationResult.Complete(redirectUri, state, code, sessionId, idTokenData) =>
          for
            idToken <- idTokenData match
              case Some(data) => serializeIdToken(data).map(Some(_))
              case None => ZIO.none
            params = List("code" -> Base64Url.encode(code)) ++
              state.map("state" -> _) ++
              idToken.map("id_token" -> _)
            redirectUrl = redirectUri.addQueryParams(params)
          yield Response.seeOther(redirectUrl)
            .addCookie(
              SessionCookie(
                value = sessionId,
                ttl = config.security.ssoSession.ttl,
              ),
            )

    private def serializeIdToken(data: ConversationResult.IdTokenData): Task[String] =
      JWT.serialize(
        typ = JWT.Type.JWT,
        claims = JWT.Claims(
          issuer = config.jwt.issuer,
          subject = data.userId.toString,
          audience = List(data.clientId),
          custom = Json.Obj(Chunk.fromIterable(data.claims)),
        ),
        ttl = 15.minutes,
        signature = JWT.Signature.Asymmetric(
          algorithm = config.jwt.publicKeys.active.algorithm,
          keyId = config.jwt.publicKeys.active.id,
          privateKey = config.jwt.privateKey,
        ),
      )

    private def formFor(
        step: ConversationStep,
        clientId: ClientId,
        locale: Option[List[String]],
    ): Task[Option[FormRenderInfo]] =
      val formId = step match
        case _: ConversationStep.Credential => "credential"
        case _: ConversationStep.Password => "password"
        case _: ConversationStep.Otp => "otp"
      for
        view    <- stepView(step, clientId)
        formOpt <- configuration.getForm(formId)
      yield formOpt.map { form =>
        val (chosenLocale, translations) = pickTranslations(form, locale)
        val allLocales = form.localizations.keys.toList.sorted
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
          ),
          version = form.version,
        )
      }

    private def pageTitle(translations: Map[String, String]): String =
      translations.getOrElse("page_title", "Sign In")

    private def pickTranslations(form: FormRecord, locale: Option[List[String]]): (String, Map[String, String]) =
      val base = form.localizations.getOrElse("en", Map.empty)
      val chosenLocale = locale.getOrElse(Nil).iterator
        .find(form.localizations.contains)
        .getOrElse("en")
      val chosen = form.localizations.getOrElse(chosenLocale, Map.empty)
      (chosenLocale, base ++ chosen)

    private def themeCss(themeId: String): UIO[String] =
      configuration.getTheme(themeId).flatMap {
        case Some(theme) if theme.css.nonEmpty => ZIO.succeed(theme.css)
        case _ => configuration.getTheme(ThemeDefault).map(_.map(_.css).getOrElse(""))
      }

    private def stepView(step: ConversationStep, clientId: ClientId): UIO[StepView] =
      step match
        case ConversationStep.Credential(primaryCredentials, inlinePassword, passkey) =>
          if primaryCredentials.contains(PrimaryCredential.phone) then
            configuration.getAllowedPhonePrefixes(clientId).map: allowedPhonePrefixes =>
              StepView.Credential(primaryCredentials, inlinePassword, passkey, Some(allowedPhonePrefixes))
          else
            ZIO.succeed(StepView.Credential(primaryCredentials, inlinePassword, passkey, None))
        case _: ConversationStep.Password => ZIO.succeed(StepView.Password)
        case _: ConversationStep.Otp => ZIO.succeed(StepView.Otp(length = 6, resendAfter = 60))

    private def solidPage(info: FormRenderInfo, themeCss: String): String =
      s"""<!DOCTYPE html>
         |<html lang="en">
         |  <head>
         |    <meta charset="UTF-8">
         |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
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