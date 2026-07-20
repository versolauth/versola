package versola.e2e.support

import zio.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*

import scala.annotation.targetName

/** Result of a successful [[OAuthClient.authorize]] call.
  *
  * @param response           the raw HTTP response (for status / location assertions)
  * @param verifier           PKCE code verifier — pass to [[OAuthClient.token]]
  * @param state              opaque state value echoed back by the server
  * @param conversationCookie the SSO_CONVERSATION cookie value — pass to challenge endpoints
  */
extension (response: Response)
  def location: String = response.header(Header.Location).map(_.url.encode).getOrElse("")

case class AuthorizeResult(
    response: Response,
    verifier: String,
    state: String,
    conversationCookie: String,
):
  val location: String = response.location

  def assertRedirectTo(path: String): Task[AuthorizeResult] =
    if response.status == Status.SeeOther && location.contains(path) then ZIO.succeed(this)
    else ZIO.fail(RuntimeException(s"Expected redirect to '$path', got status=${response.status} location=$location"))

  def assertChallengeRedirect: Task[AuthorizeResult] = assertRedirectTo("/challenge")

extension (task: Task[AuthorizeResult])
  def assertRedirectTo(path: String): Task[AuthorizeResult] = task.flatMap(_.assertRedirectTo(path))
  def assertChallengeRedirect: Task[AuthorizeResult] = task.flatMap(_.assertChallengeRedirect)

case class ChallengeResult(response: Response, html: String):
  val step: Option[ConversationStep] = ConversationStep.fromHtml(html)

  def assertStep(expected: ConversationStep): Task[ChallengeResult] =
    step.assertIs(expected).as(this)

extension (task: Task[ChallengeResult])
  def assertStep(expected: ConversationStep): Task[ChallengeResult] = task.flatMap(_.assertStep(expected))

sealed trait TokenResult:
  def response: Response
  def success: Task[TokenResult.Success] = this match
    case s: TokenResult.Success => ZIO.succeed(s)
    case TokenResult.Failure(resp, body) => ZIO.fail(RuntimeException(s"Expected token success but got: status=${resp.status} body=$body"))

object TokenResult:
  case class Success(
      response: Response,
      accessToken: String,
      tokenType: String,
      expiresIn: Long,
  ) extends TokenResult

  case class Failure(response: Response, body: String) extends TokenResult

  private case class Raw(access_token: String, token_type: String, expires_in: Long) derives JsonDecoder

  def parse(response: Response): Task[TokenResult] =
    response.body.asString.map: body =>
      if response.status.isSuccess then
        body.fromJson[Raw].fold(
          err => Failure(response, s"JSON parse error [$err] body=$body"),
          raw => Success(response, raw.access_token, raw.token_type, raw.expires_in),
        )
      else Failure(response, body)

extension (task: Task[TokenResult])
  @targetName("tokenSuccess")
  def success: Task[TokenResult.Success] = task.flatMap(_.success)

sealed trait UserinfoResult:
  def response: Response
  def success: Task[UserinfoResult.Success] = this match
    case s: UserinfoResult.Success => ZIO.succeed(s)
    case UserinfoResult.Failure(resp, body) => ZIO.fail(RuntimeException(s"Expected userinfo success but got: status=${resp.status} body=$body"))

object UserinfoResult:
  case class Success(
      response: Response,
      sub: java.util.UUID,
      email: Option[String],
      name: Option[String],
      givenName: Option[String],
      familyName: Option[String],
      phoneNumber: Option[String],
  ) extends UserinfoResult

  case class Failure(response: Response, body: String) extends UserinfoResult

  private case class Raw(
      sub: String,
      email: Option[String],
      name: Option[String],
      @jsonField("given_name") givenName: Option[String],
      @jsonField("family_name") familyName: Option[String],
      @jsonField("phone_number") phoneNumber: Option[String],
  ) derives JsonDecoder

  def parse(response: Response): Task[UserinfoResult] =
    response.body.asString.flatMap: body =>
      if response.status.isSuccess then
        body.fromJson[Raw].fold(
          err => ZIO.succeed(Failure(response, s"JSON parse error [$err] body=$body")),
          raw =>
            ZIO.attempt(java.util.UUID.fromString(raw.sub))
              .map(uuid => Success(response, uuid, raw.email, raw.name, raw.givenName, raw.familyName, raw.phoneNumber))
              .orElse(ZIO.succeed(Failure(response, s"sub is not a valid UUID: ${raw.sub}"))),
        )
      else ZIO.succeed(Failure(response, body))

extension (task: Task[UserinfoResult])
  @targetName("userinfoSuccess")
  def success: Task[UserinfoResult.Success] = task.flatMap(_.success)

sealed trait RegisterClientResult:
  def response: Response
  def success: Task[RegisterClientResult.Success] = this match
    case s: RegisterClientResult.Success => ZIO.succeed(s)
    case RegisterClientResult.Failure(resp, body) =>
      ZIO.fail(RuntimeException(s"Expected registerClient success but got: status=${resp.status} body=$body"))

object RegisterClientResult:
  case class Success(response: Response, secret: String) extends RegisterClientResult
  case class Failure(response: Response, body: String) extends RegisterClientResult

  private case class Raw(secret: String) derives JsonDecoder

  def parse(response: Response): Task[RegisterClientResult] =
    response.body.asString.map: body =>
      if response.status.isSuccess then
        body.fromJson[Raw].fold(
          err => Failure(response, s"JSON parse error [$err] body=$body"),
          raw => Success(response, raw.secret),
        )
      else Failure(response, body)

extension (task: Task[RegisterClientResult])
  @targetName("registerClientSuccess")
  def success: Task[RegisterClientResult.Success] = task.flatMap(_.success)

case class SubmitResult(response: Response):
  val location: String = response.location
  /** `SSO_SESSION` cookie set by the server when an auth conversation finishes. */
  val sessionCookie: Option[String] =
    response.headers.getAll(Header.SetCookie)
      .collectFirst { case h if h.value.name == "SSO_SESSION" => h.value.content }

  def assertRedirect: Task[String] =
    if response.status == Status.SeeOther then
      ZIO.fromEither(URL.decode(location)).flatMap: url =>
        url.queryZIO[String]("code")
          .orElseFail(RuntimeException(s"Expected code in redirect location, got: $location"))
    else ZIO.fail(RuntimeException(s"Expected redirect with code, got status=${response.status} location=$location"))

extension (task: Task[SubmitResult])
  def assertRedirect: Task[String] = task.flatMap(_.assertRedirect)

  /** Like [[assertRedirect]], but when the redirect goes to `/challenge` it
    * fetches the current challenge step and includes it in the failure message.
    */
  def assertRedirect(client: OAuthClient, cookie: String): Task[String] =
    task.flatMap: result =>
      if result.response.status == Status.SeeOther then
        ZIO.fromEither(URL.decode(result.location)).flatMap: url =>
          url.queryZIO[String]("code").orElse:
            if result.location.contains("/challenge") then
              client.getChallenge(cookie).flatMap: challenge =>
                ZIO.fail(RuntimeException(
                  s"Expected code but got redirect to challenge: step=${challenge.step.map(_.toString).getOrElse("unknown")}",
                ))
            else
              ZIO.fail(RuntimeException(s"Expected code in redirect location, got: ${result.location}"))
      else
        ZIO.fail(RuntimeException(
          s"Expected redirect with code, got status=${result.response.status} location=${result.location}",
        ))

extension (task: Task[Response])
  /** Assert the response is a 303 redirect to the redirect_uri carrying an authorization code. */
  def assertCodeRedirect: Task[String] =
    task.flatMap: response =>
      if response.status != Status.SeeOther then
        ZIO.fail(RuntimeException(s"Expected 303 code redirect, got status=${response.status}"))
      else
        ZIO.fromEither(URL.decode(response.location)).flatMap: url =>
          url.queryZIO[String]("code")
            .orElseFail(RuntimeException(s"Expected code in redirect location, got: ${response.location}"))

  /** Assert the response is a 303 error redirect and that the OAuth `error` param matches. */
  def assertErrorRedirect(expectedError: String): Task[Unit] =
    task.flatMap: response =>
      if response.status != Status.SeeOther then
        ZIO.fail(RuntimeException(s"Expected 303 error redirect, got status=${response.status}"))
      else
        ZIO.fromEither(URL.decode(response.location)).flatMap: url =>
          url.queryZIO[String]("error").flatMap: actualError =>
            if actualError == expectedError then ZIO.unit
            else ZIO.fail(RuntimeException(s"Expected error='$expectedError', got error='$actualError'"))

/** Thin HTTP client for e2e tests.
  *
  * Intentionally does NOT follow redirects — each hop is its own assertion.
  * Cookies are threaded explicitly so tests can inspect every step.
  */
final class OAuthClient(client: Client, config: E2EConfig):

  /** GET /authorize — generates PKCE + state, starts a new conversation, extracts the
    * SSO_CONVERSATION cookie, and returns everything the caller needs for subsequent steps.
    */
  def authorize(
      scope: String = "openid",
      clientId: Option[String] = None,
      redirectUri: Option[String] = None,
  ): Task[AuthorizeResult] =
    val effectiveClientId = clientId.getOrElse(config.clientId)
    val effectiveRedirectUri = redirectUri.getOrElse(config.redirectUri)
    val (verifier, challenge) = PkceHelper.generate()
    val state = java.util.UUID.randomUUID().toString
    for
      base <- ZIO.fromEither(URL.decode(s"${config.authUrl}/authorize")).mapError(new RuntimeException(_))
      url = base.addQueryParams(List(
        "response_type" -> "code",
        "client_id" -> effectiveClientId,
        "redirect_uri" -> effectiveRedirectUri,
        "scope" -> scope,
        "state" -> state,
        "code_challenge" -> challenge,
        "code_challenge_method" -> "S256",
      ))
      response <- Client.batched(Request.get(url)).provide(ZLayer.succeed(client))
      cookie <- ZIO.fromOption(OAuthClient.extractConversationCookie(response))
        .orElseFail(new RuntimeException(
          s"No SSO_CONVERSATION cookie in /authorize response (status=${response.status})",
        ))
    yield AuthorizeResult(response, verifier, state, cookie)

  /** GET /authorize — raw variant with full control over which parameters are sent.
    * Parameters set to [[None]] are omitted from the request.
    * Returns the bare [[Response]]; useful for negative test cases.
    */
  def authorizeRaw(
      clientId: String,
      redirectUri: String,
      scope: Option[String] = Some("openid"),
      responseType: Option[String] = Some("code"),
      codeChallenge: Option[String] = None,
      codeChallengeMethod: Option[String] = Some("S256"),
      state: Option[String] = None,
      prompt: Option[String] = None,
      maxAge: Option[Long] = None,
      sessionCookie: Option[String] = None,
      acrValues: Option[String] = None,
  ): Task[Response] =
    for
      base <- ZIO.fromEither(URL.decode(s"${config.authUrl}/authorize")).mapError(new RuntimeException(_))
      params = List(
        "client_id"            -> Some(clientId),
        "redirect_uri"         -> Some(redirectUri),
        "scope"                -> scope,
        "response_type"        -> responseType,
        "code_challenge"       -> codeChallenge,
        "code_challenge_method"-> codeChallengeMethod,
        "state"                -> state,
        "prompt"               -> prompt,
        "max_age"              -> maxAge.map(_.toString),
        "acr_values"           -> acrValues,
      ).collect { case (k, Some(v)) => k -> v }
      req = Request.get(base.addQueryParams(params))
      reqWithSession = sessionCookie.fold(req)(sc =>
        req.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request("SSO_SESSION", sc))))
      )
      response <- Client.batched(reqWithSession).provide(ZLayer.succeed(client))
    yield response

  /** GET /challenge — fetches the current challenge form HTML and parses the step meta tag. */
  def getChallenge(cookie: String): Task[ChallengeResult] =
    Client.batched(
      Request.get(s"${config.authUrl}/challenge")
        .addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request("SSO_CONVERSATION", cookie)))),
    ).provide(ZLayer.succeed(client))
      .flatMap(resp => resp.body.asString.map(ChallengeResult(resp, _)))

  /** POST /challenge/email — submits an email credential to start the OTP flow. */
  def submitEmail(cookie: String, email: String): Task[SubmitResult] =
    formPost(s"${config.authUrl}/challenge/email", Map("email" -> email), cookie).map(SubmitResult(_))

  /** POST /challenge/otp — submits an OTP code (always "123456" in non-prod). */
  def submitOtp(cookie: String, code: String): Task[SubmitResult] =
    formPost(s"${config.authUrl}/challenge/otp", Map("code" -> code), cookie).map(SubmitResult(_))

  /** POST /challenge/set-password — submits a new password when the conversation requires it. */
  def submitSetPassword(cookie: String, password: String): Task[SubmitResult] =
    formPost(s"${config.authUrl}/challenge/set-password", Map("password" -> password), cookie).map(SubmitResult(_))

  /** POST /challenge/login-password — submits login + password in a single step. */
  def submitLoginPassword(cookie: String, login: String, password: String): Task[SubmitResult] =
    formPost(
      s"${config.authUrl}/challenge/login-password",
      Map("login" -> login, "password" -> password),
      cookie,
    ).map(SubmitResult(_))

  /** POST /token — exchanges authorization code for tokens. */
  def token(
      code: String,
      verifier: String,
      clientId: Option[String] = None,
      clientSecret: Option[String] = None,
      redirectUri: Option[String] = None,
  ): Task[TokenResult] =
    val effectiveClientId = clientId.getOrElse(config.clientId)
    val effectiveClientSecret = clientSecret.getOrElse(config.clientSecret)
    val effectiveRedirectUri = redirectUri.getOrElse(config.redirectUri)
    val body = formBody(Map(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "redirect_uri" -> effectiveRedirectUri,
      "code_verifier" -> verifier,
    ))
    val req = Request.post(s"${config.authUrl}/token", body)
      .addHeader(Authorization.Basic(effectiveClientId, effectiveClientSecret))
      .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    Client.batched(req).provide(ZLayer.succeed(client)).flatMap(TokenResult.parse)

  /** POST /users — registers a new user via the Central API, returns the generated user ID. */
  def registerUser(email: Option[String] = None, login: Option[String] = None): Task[java.util.UUID] =
    import zio.json.ast.Json
    val fields = List(
      email.map("email" -> Json.Str(_)),
      login.map("login" -> Json.Str(_)),
    ).flatten
    val body = Body.fromString(Json.Obj(fields*).toJson)
    val req = Request.post(s"${config.centralUrl}/users", body)
      .addHeader(Authorization.Basic(config.clientId, config.clientSecret))
      .addHeader(Header.ContentType(MediaType.application.json))
    Client.batched(req).provide(ZLayer.succeed(client)).flatMap: resp =>
      resp.body.asString.flatMap: bodyStr =>
        if resp.status.isSuccess then
          ZIO.fromEither(bodyStr.fromJson[OAuthClient.CreateUserResponseBody])
            .mapError(err => RuntimeException(s"Failed to parse registerUser response [$err]: $bodyStr"))
            .flatMap(r =>
              ZIO.attempt(java.util.UUID.fromString(r.id))
                .mapError(err => RuntimeException(s"registerUser: id is not a UUID [${r.id}]: $err")),
            )
        else
          ZIO.fail(RuntimeException(s"registerUser failed: status=${resp.status} body=$bodyStr"))

  /** POST /users/password/set — sets a permanent password for a user (non-prod only). */
  def setUserPassword(userId: java.util.UUID, password: String): Task[Unit] =
    import zio.json.ast.Json
    val body = Body.fromString(
      Json.Obj("userId" -> Json.Str(userId.toString), "password" -> Json.Str(password)).toJson,
    )
    val req = Request.post(s"${config.centralUrl}/users/password/set", body)
      .addHeader(Authorization.Basic(config.clientId, config.clientSecret))
      .addHeader(Header.ContentType(MediaType.application.json))
    Client.batched(req).provide(ZLayer.succeed(client)).flatMap: resp =>
      if resp.status.isSuccess then ZIO.unit
      else
        resp.body.asString.flatMap: bodyStr =>
          ZIO.fail(RuntimeException(s"setUserPassword failed: status=${resp.status} body=$bodyStr"))

  /** POST /configuration/clients — registers a new OAuth client via the Central API.
    * Authenticates with the central-admin secret (same credential as [[token]]).
    */
  def registerClient(
      clientId: String,
      clientName: String,
      redirectUris: Set[String],
      tenantId: String = "default",
      allowedScopes: Set[String] = Set("openid"),
      authFlow: Option[zio.json.ast.Json] = None,
  ): Task[RegisterClientResult] =
    val body = Body.fromString(OAuthClient.RegisterClientBody(
      tenantId = tenantId,
      id = clientId,
      clientName = clientName,
      redirectUris = redirectUris,
      allowedScopes = allowedScopes,
      audience = List.empty,
      permissions = Set.empty,
      accessTokenTtl = 3600,
      refreshTokenTtl = None,
      theme = "default",
      authFlow = authFlow,
      otpTemplateId = "default",
    ).toJson)
    val req = Request.post(s"${config.centralUrl}/configuration/clients", body)
      .addHeader(Authorization.Basic(config.clientId, config.clientSecret))
      .addHeader(Header.ContentType(MediaType.application.json))
    Client.batched(req).provide(ZLayer.succeed(client)).flatMap(RegisterClientResult.parse)

  /** POST /users/outbox/flush — forces central to dispatch all pending user-outbox events to auth (non-prod only). */
  def flushUserOutbox(): Task[Unit] =
    Client.batched(Request.post(s"${config.centralUrl}/service/users/outbox/flush", Body.empty))
      .provide(ZLayer.succeed(client))
      .flatMap: resp =>
        if resp.status.isSuccess then ZIO.unit
        else
          resp.body.asString.flatMap: body =>
            ZIO.fail(RuntimeException(s"flushUserOutbox failed: status=${resp.status} body=$body"))

  /** POST /configuration/sync — forces auth to reload all configuration caches from central (non-prod only). */
  def syncConfiguration(): Task[Unit] =
    Client.batched(Request.post(s"${config.authUrl}/service/configuration/sync", Body.empty))
      .provide(ZLayer.succeed(client))
      .flatMap: resp =>
        if resp.status.isSuccess then ZIO.unit
        else
          resp.body.asString.flatMap: body =>
            ZIO.fail(RuntimeException(s"syncConfiguration failed: status=${resp.status} body=$body"))

  /** PUT /configuration/challenges/challenge-settings — sets the ACR vocabulary for a tenant (non-prod only).
    *
    * All other fields are overwritten with minimal test-safe defaults.
    * Call [[syncConfiguration]] afterwards to make Auth reload the cache.
    */
  def upsertChallengeSettings(
      tenantId: String = "default",
      acrVocabulary: Map[String, List[String]] = Map.empty,
  ): Task[Unit] =
    val vocabJson =
      if acrVocabulary.isEmpty then "null"
      else
        acrVocabulary.map { case (k, vs) =>
          s""""$k": [${vs.map(v => s""""$v"""").mkString(",")}]"""
        }.mkString("{", ",", "}")
    val bodyStr =
      s"""{
         |  "tenantId": "$tenantId",
         |  "allowedPrefixes": [],
         |  "submissionLimits": {"otpRequest":[],"otpSubmit":[],"passwordSubmit":[],"passkeyAssertion":[],"banDurationSeconds":0},
         |  "otpLength": 6,
         |  "otpResendAfter": 60,
         |  "passkeySettings": {"rpId":"localhost","rpName":"Versola","origins":["http://localhost:3000"],"userVerification":"preferred"},
         |  "ipHeader": "X-Forwarded-For",
         |  "acrVocabulary": $vocabJson
         |}""".stripMargin
    val req = Request.put(s"${config.centralUrl}/configuration/challenges/challenge-settings", Body.fromString(bodyStr))
      .addHeader(Authorization.Basic(config.clientId, config.clientSecret))
      .addHeader(Header.ContentType(MediaType.application.json))
    Client.batched(req).provide(ZLayer.succeed(client)).flatMap: resp =>
      if resp.status.isSuccess then ZIO.unit
      else
        resp.body.asString.flatMap: body =>
          ZIO.fail(RuntimeException(s"upsertChallengeSettings failed: status=${resp.status} body=$body"))

  /** GET /userinfo — fetches claims for a bearer token. */
  def userinfo(accessToken: String): Task[UserinfoResult] =
    Client.batched(
      Request.get(s"${config.authUrl}/userinfo")
        .addHeader(Authorization.Bearer(accessToken)),
    ).provide(ZLayer.succeed(client)).flatMap(UserinfoResult.parse)

  // ── helpers ────────────────────────────────────────────────────────────────

  private def formPost(url: String, fields: Map[String, String], cookie: String): Task[Response] =
    val req = Request.post(url, formBody(fields))
      .addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request("SSO_CONVERSATION", cookie))))
      .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    Client.batched(req).provide(ZLayer.succeed(client))

  private def formBody(fields: Map[String, String]): Body =
    val encoded = fields.map { case (k, v) => s"${encode(k)}=${encode(v)}" }.mkString("&")
    Body.fromString(encoded)

  private def encode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

object OAuthClient:

  val live: ZLayer[Client & E2EConfig, Nothing, OAuthClient] =
    ZLayer.fromFunction(OAuthClient(_, _))

  private[support] def extractConversationCookie(response: Response): Option[String] =
    response.headers.getAll(Header.SetCookie)
      .collectFirst { case h if h.value.name == "SSO_CONVERSATION" => h.value.content }

  private[support] case class CreateUserResponseBody(id: String) derives JsonDecoder

  /** Minimal mirror of `CreateClientRequest` for e2e JSON serialisation. */
  private[support] case class RegisterClientBody(
      tenantId: String,
      id: String,
      clientName: String,
      redirectUris: Set[String],
      allowedScopes: Set[String],
      audience: List[String],
      permissions: Set[String],
      accessTokenTtl: Int,
      refreshTokenTtl: Option[Int],
      theme: String,
      authFlow: Option[zio.json.ast.Json],
      otpTemplateId: String,
  ) derives JsonEncoder
