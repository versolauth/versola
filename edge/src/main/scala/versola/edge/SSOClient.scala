package versola.edge

import versola.edge.model.{AccessToken, AuthorizationPreset, ClientId, Code, CodeVerifier, RefreshToken, State, TokenResponse}
import versola.util.{Base64, RedirectUri, Secret}
import zio.http.*
import zio.json.ast.Json
import zio.json.{JsonCodec, jsonField}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{IO, Task, UIO, URLayer, ZIO, ZLayer}

trait SSOClient:
  def authorizeUri(
      preset: AuthorizationPreset,
      codeChallenge: String,
      state: State,
  ): UIO[URL]

  def exchangeAuthorizationCode(
      code: Code,
      codeVerifier: CodeVerifier,
      redirectUri: RedirectUri,
      clientId: ClientId,
      clientSecret: Secret,
  ): Task[TokenResponse]

  def exchangeRefreshToken(
      refreshToken: RefreshToken,
      clientId: ClientId,
      clientSecret: Secret,
  ): IO[Throwable | SSOClient.InvalidGrant.type, TokenResponse]

  def userInfo(
      accessToken: AccessToken,
  ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj]

object SSOClient:
  case object InvalidGrant
  case object UserInfoUnauthorized

  private case class ErrorResponse(
      error: String,
      @jsonField("error_description") errorDescription: Option[String] = None,
  ) derives JsonCodec

  val live: URLayer[Client & EdgeConfig, SSOClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
  ) extends SSOClient:
    private val authorizeUrl: URL = config.versolaUrl / "authorize"
    private val tokenUrl: URL = config.versolaUrl / "token"
    private val userInfoUrl = config.versolaUrl / "userinfo"

    override def authorizeUri(
        preset: AuthorizationPreset,
        codeChallenge: String,
        state: State,
    ): UIO[URL] = ZIO.succeed:
      val params = List(
        "client_id" -> preset.clientId,
        "redirect_uri" -> preset.redirectUri,
        "response_type" -> preset.responseType,
        "scope" -> preset.scope.mkString(" "),
        "code_challenge" -> codeChallenge,
        "code_challenge_method" -> "S256",
        "state" -> state,
      ) ++ preset.uiLocales.map(locales => "ui_locales" -> locales.mkString(" "))
        ++ preset.customParameters.flatMap { case (key, values) =>
          values.map(value => key -> value)
        }

      authorizeUrl.addQueryParams(params)

    override def exchangeAuthorizationCode(
        code: Code,
        codeVerifier: CodeVerifier,
        redirectUri: RedirectUri,
        clientId: ClientId,
        clientSecret: Secret,
    ): Task[TokenResponse] =
      val form = Form(
        FormField.simpleField("grant_type", "authorization_code"),
        FormField.simpleField("code", code),
        FormField.simpleField("redirect_uri", redirectUri),
        FormField.simpleField("code_verifier", codeVerifier),
      )
      val request = Request
        .post(tokenUrl, Body.fromURLEncodedForm(form))
        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
        .addHeader(Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret)))

      for
        response <- ZIO.scoped(httpClient.request(request))
        tokenResponse <-
          if response.status.isSuccess then response.bodyAs[TokenResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              ZIO.fail(new RuntimeException(s"Authorization code exchange failed: ${response.status.code} ${error.error}${error.errorDescription.fold("")(d => s" - $d")}"))
      yield tokenResponse

    override def exchangeRefreshToken(
        refreshToken: RefreshToken,
        clientId: ClientId,
        clientSecret: Secret,
    ): IO[Throwable | InvalidGrant.type, TokenResponse] =
      val form = Form(
        FormField.simpleField("grant_type", "refresh_token"),
        FormField.simpleField("refresh_token", refreshToken),
      )
      val request = Request
        .post(tokenUrl, Body.fromURLEncodedForm(form))
        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
        .addHeader(Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret)))

      for
        response <- ZIO.scoped(httpClient.request(request))
        result <-
          if response.status.isSuccess then response.bodyAs[TokenResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              if error.error == "invalid_grant" then ZIO.fail(InvalidGrant)
              else ZIO.fail(new RuntimeException(s"Token exchange failed: ${response.status.code} ${error.error}"))
      yield result

    override def userInfo(
        accessToken: AccessToken,
    ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj] =
      val request = Request
        .get(userInfoUrl)
        .addHeader(Header.Authorization.Bearer(accessToken.toString))

      for
        response <- ZIO.scoped(httpClient.request(request))

        result <-
          if response.status.isSuccess then
            response.bodyAs[Json].flatMap {
              case obj: Json.Obj =>
                ZIO.succeed(obj)

              case _ =>
                ZIO.fail(
                  new RuntimeException("UserInfo endpoint returned non-object JSON"),
                )
            }
          else if response.status == Status.Unauthorized then
            ZIO.fail(SSOClient.UserInfoUnauthorized)
          else
            ZIO.fail(
              new RuntimeException(
                s"UserInfo request failed with status ${response.status.code}",
              ),
            )
      yield result
