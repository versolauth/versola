package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*
import zio.{Duration, IO, ZIO, ZLayer}

/** The `/token` response, with only the fields a driver uses. A `derives JsonDecoder` case
  * class rather than the e2e client's `Json.Obj`: the AST costs one allocation per field per
  * response, on the one endpoint every single session hits (§3.2).
  */
private case class TokenResponseBody(
    access_token: String,
    expires_in: Long,
    refresh_token: Option[String],
    id_token: Option[String],
) derives JsonDecoder

/** The `error` of an RFC 6749 §5.2 failure. `error_description` is deliberately not read: auth
  * returns the same description for every `invalid_grant` cause, so it carries no information
  * the code below could branch on.
  */
private case class TokenErrorBody(error: String) derives JsonDecoder

/** The auth endpoints, decoded once.
  *
  * `URL.decode` per request is both an allocation and a parse on the hot path, and a base URL
  * that does not parse is a configuration fault that must surface when the client is built, not
  * as a per-request failure from inside a fiber (§3.2's "no throwing from constructor `val`s").
  */
private case class AuthEndpoints(
    authorize: URL,
    challenge: URL,
    challengePhone: URL,
    challengeOtp: URL,
    challengePassword: URL,
    challengeSetPassword: URL,
    challengeLoginPassword: URL,
    challengePasskey: URL,
    challengePasskeyOptions: URL,
    token: URL,
    logout: URL,
)

private object AuthEndpoints:
  def from(authUrl: String): Either[ProtocolError, AuthEndpoints] =
    def at(path: String): Either[ProtocolError, URL] =
      URL.decode(authUrl + path).left.map(error => ProtocolError.Misconfigured(authUrl + path + ": " + error.getMessage))

    for
      authorize <- at("/authorize")
      challenge <- at("/challenge")
      phone <- at("/challenge/phone")
      otp <- at("/challenge/otp")
      password <- at("/challenge/password")
      setPassword <- at("/challenge/set-password")
      loginPassword <- at("/challenge/login-password")
      passkey <- at("/challenge/passkey")
      passkeyOptions <- at("/challenge/passkey/options")
      token <- at("/token")
      logout <- at("/logout")
    yield AuthEndpoints(
      authorize,
      challenge,
      phone,
      otp,
      password,
      setPassword,
      loginPassword,
      passkey,
      passkeyOptions,
      token,
      logout,
    )

/** [[AuthClient]] against the real SUT: the `mobile-*` clients of §8.1-8.3, their refresh
  * (§8.5), and RP-initiated logout.
  *
  * Request shapes -- URLs, form field names, cookie names, the Basic-versus-body choice at
  * `/token` -- are the e2e `OAuthClient`'s, which is the hard-won part (§3.2). Everything around
  * them is not: no regex is compiled per page, no `ZLayer` is built per request, no body becomes
  * a `Json.Obj`, nothing throws, and no failure string is built on a path that succeeds.
  */
final class HttpAuthClient(exchange: HttpExchange, endpoints: AuthEndpoints, clients: ClientRegistry) extends AuthClient:
  import HttpAuthClient.*

  override def authorize(
      scope: String,
      clientId: Option[String],
      acrValues: Option[List[String]],
      sessionCookie: Option[SsoSession],
  ): IO[ProtocolError, AuthorizeOutcome] =
    for
      registration <- ZIO.fromEither(clients.resolve(clientId))
      pkce = Pkce.generate()
      state = Entropy.nonce()
      params = List(
        "response_type" -> "code",
        "client_id" -> registration.creds.clientId,
        "redirect_uri" -> registration.redirectUri,
        "scope" -> scope,
        "state" -> state,
        "code_challenge" -> pkce.challenge,
        "code_challenge_method" -> "S256",
      ) ++ acrValues.map(values => "acr_values" -> values.mkString(" "))
      request = Request.get(endpoints.authorize.addQueryParams(params))
      withSession = sessionCookie.fold(request)(session => request.addHeader(HttpExchange.cookieHeader(ssoSessionCookie, session.value)))
      received <- exchange.send(withSession)
      outcome <- HttpExchange.setCookie(received.response, conversationCookie) match
        case Some(cookie) =>
          ZIO.succeed(AuthorizeOutcome.Started(AuthorizeStarted(ConversationCookie(cookie), pkce.verifier, state)))
        case None =>
          for
            location <- HttpExchange.required(
              received.location,
              authorizeEndpoint,
              "no " + conversationCookie + " cookie and no redirect Location on the /authorize response",
            )
            code <- HttpExchange.required(
              HttpExchange.redirectParam(location, "code"),
              authorizeEndpoint,
              "no " + conversationCookie + " cookie and no code on the /authorize redirect",
            )
          yield AuthorizeOutcome.Authorized(AuthCode(code), pkce.verifier)
    yield outcome

  override def challenge(conversation: ConversationCookie): IO[ProtocolError, ChallengePage] =
    exchange
      .send(Request.get(endpoints.challenge).addHeader(HttpExchange.cookieHeader(conversationCookie, conversation.value)))
      .flatMap: received =>
        if received.status == Status.Ok then ZIO.succeed(ChallengePage.parse(conversation, received.body))
        else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, challengeEndpoint))

  override def submitPhone(conversation: ConversationCookie, phone: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome] =
    submit(endpoints.challengePhone, phoneEndpoint, conversation, List("phone" -> phone, "csrf" -> csrf.value))

  override def submitOtp(conversation: ConversationCookie, code: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome] =
    submit(endpoints.challengeOtp, otpEndpoint, conversation, List("code" -> code, "csrf" -> csrf.value))

  override def submitPassword(conversation: ConversationCookie, password: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome] =
    submit(endpoints.challengePassword, passwordEndpoint, conversation, List("password" -> password, "csrf" -> csrf.value))

  override def submitSetPassword(conversation: ConversationCookie, password: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome] =
    submit(endpoints.challengeSetPassword, setPasswordEndpoint, conversation, List("password" -> password, "csrf" -> csrf.value))

  override def submitLoginPassword(
      conversation: ConversationCookie,
      login: String,
      password: String,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome] =
    submit(
      endpoints.challengeLoginPassword,
      loginPasswordEndpoint,
      conversation,
      List("login" -> login, "password" -> password, "csrf" -> csrf.value),
    )

  override def passkeyOptions(conversation: ConversationCookie): IO[ProtocolError, String] =
    exchange
      .send(Request.get(endpoints.challengePasskeyOptions).addHeader(HttpExchange.cookieHeader(conversationCookie, conversation.value)))
      .flatMap: received =>
        if received.status == Status.Ok then ZIO.succeed(received.body)
        else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, passkeyOptionsEndpoint))

  override def submitPasskeyAssertion(
      conversation: ConversationCookie,
      assertionJson: String,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome] =
    submit(endpoints.challengePasskey, passkeyEndpoint, conversation, List("response" -> assertionJson, "csrf" -> csrf.value))

  override def exchangeCode(code: AuthCode, verifier: CodeVerifier, client: ClientCreds): IO[ProtocolError, Tokens] =
    for
      registration <- ZIO.fromEither(clients.resolve(Some(client.clientId)))
      fields = List(
        "grant_type" -> "authorization_code",
        "code" -> code.value,
        "redirect_uri" -> registration.redirectUri,
        "code_verifier" -> verifier.value,
      )
      received <- exchange.send(tokenRequest(fields, client))
      tokens <-
        if received.status == Status.Ok then decodeTokens(received.body)
        else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, tokenEndpoint))
    yield tokens

  override def exchangeRefresh(token: RefreshToken, client: ClientCreds): IO[ProtocolError, Tokens] =
    exchange
      .send(tokenRequest(List("grant_type" -> "refresh_token", "refresh_token" -> token.value), client))
      .flatMap: received =>
        if received.status == Status.Ok then decodeTokens(received.body)
        // A 400 is the token endpoint rejecting the refresh grant itself (RFC 6749 §5.2) --
        // the only thing `loadgen_refresh_rejected_total` (§11) is supposed to measure.
        else if received.status == Status.BadRequest then ZIO.fail(ProtocolError.RefreshRejected(refreshRejection(received)))
        // A 401 here is `invalid_client`: the emulator's own client credentials are wrong or
        // stale, not the SUT rejecting a refresh -- polluting the RefreshRejected metric with
        // this would defeat its purpose (§7.4).
        else if received.status == Status.Unauthorized then
          ZIO.fail(ProtocolError.Misconfigured("token endpoint rejected client credentials on refresh"))
        else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, tokenEndpoint))

  override def logout(idToken: IdToken): IO[ProtocolError, Unit] =
    exchange
      .send(Request.get(endpoints.logout.addQueryParam("id_token_hint", idToken.value)))
      .flatMap: received =>
        // The SUT answers a hint-only logout with either the confirmation page or a redirect on
        // to the post-logout URI; both mean the session is gone.
        if received.status == Status.Ok || HttpExchange.isRedirect(received.status) then ZIO.unit
        else ZIO.fail(HttpExchange.unexpected(expectedLogout, received.status, logoutEndpoint))

  private def submit(
      url: URL,
      endpoint: String,
      conversation: ConversationCookie,
      fields: List[(String, String)],
  ): IO[ProtocolError, SubmitOutcome] =
    val request = Request
      .post(url, HttpExchange.formBody(fields))
      .addHeader(HttpExchange.cookieHeader(conversationCookie, conversation.value))
      .addHeader(HttpExchange.formContentType)
    exchange.send(request).flatMap { received =>
      if HttpExchange.isRedirect(received.status) then
        HttpExchange
          .required(received.location, endpoint, "redirect without a Location header")
          .map(location => SubmitOutcome.Redirected(location, HttpExchange.setCookie(received.response, ssoSessionCookie).map(SsoSession.apply)))
      else if received.status == Status.Ok then ZIO.succeed(SubmitOutcome.Rendered(ChallengePage.parse(conversation, received.body)))
      else ZIO.fail(HttpExchange.unexpected(expectedSubmit, received.status, endpoint))
    }

  /** A confidential client authenticates with HTTP Basic; a public one (the three `mobile-*`
    * clients of design doc §2.2 hold no secret) names itself in the body and proves itself with
    * PKCE instead.
    */
  private def tokenRequest(fields: List[(String, String)], client: ClientCreds): Request =
    val request = Request
      .post(endpoints.token, HttpExchange.formBody(client.clientSecret.fold(fields :+ ("client_id" -> client.clientId))(_ => fields)))
      .addHeader(HttpExchange.formContentType)
    client.clientSecret.fold(request)(secret => request.addHeader(Authorization.Basic(client.clientId, secret)))

  private def decodeTokens(body: String): IO[ProtocolError, Tokens] =
    ZIO
      .fromEither(body.fromJson[TokenResponseBody])
      .mapBoth(
        error => ProtocolError.MalformedResponse(tokenEndpoint, error),
        raw =>
          Tokens(
            AccessToken(raw.access_token),
            raw.refresh_token.map(RefreshToken.apply),
            raw.id_token.map(IdToken.apply),
            raw.expires_in,
          ),
      )

object HttpAuthClient:
  private val conversationCookie = "SSO_CONVERSATION"
  private val ssoSessionCookie = "SSO_SESSION"

  // Endpoint labels and expected-status sets are values, not interpolations built per failure:
  // the failure path allocates nothing beyond the error itself.
  private val authorizeEndpoint = "/authorize"
  private val challengeEndpoint = "/challenge"
  private val phoneEndpoint = "/challenge/phone"
  private val otpEndpoint = "/challenge/otp"
  private val passwordEndpoint = "/challenge/password"
  private val setPasswordEndpoint = "/challenge/set-password"
  private val loginPasswordEndpoint = "/challenge/login-password"
  private val passkeyEndpoint = "/challenge/passkey"
  private val passkeyOptionsEndpoint = "/challenge/passkey/options"
  private val tokenEndpoint = "/token"
  private val logoutEndpoint = "/logout"

  private val expectedOk: Set[Status] = Set(Status.Ok)
  private val expectedSubmit: Set[Status] = Set(Status.Ok, Status.SeeOther)
  private val expectedLogout: Set[Status] = Set(Status.Ok, Status.SeeOther)

  /** Why a refresh exchange was turned down, as far as the wire allows.
    *
    * Auth answers every `invalid_grant` cause -- reuse, expiry, revocation, a token issued to
    * another client -- with a byte-identical body, on purpose: `TokenEndpointError.InvalidGrant`
    * keeps the specific reason in `logDescription`, which never leaves the server, so the
    * endpoint cannot be used as an oracle. The distinction design doc §6.3 wants between
    * `AlreadyExchanged` and `Expired` is therefore not observable here and has to come from the
    * driver's own bookkeeping (it knows the token's TTL and its own generation counter); this
    * reports what was actually received.
    */
  private def refreshRejection(received: Received): RefreshRejection =
    received.body.fromJson[TokenErrorBody] match
      case Right(error) => RefreshRejection.Unknown(error.error)
      case Left(_) => RefreshRejection.Unknown(received.status.code.toString)

  def make(client: Client, targets: TargetsConfig, clients: ClientRegistry, requestTimeout: Duration): IO[ProtocolError, AuthClient] =
    ZIO
      .fromEither(AuthEndpoints.from(targets.authUrl))
      .map(endpoints => HttpAuthClient(HttpExchange(client, requestTimeout), endpoints, clients))

  /** One client per driver pod, built once and shared by every fiber (§4). */
  val live: ZLayer[Client & TargetsConfig & ClientRegistry, ProtocolError, AuthClient] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        targets <- ZIO.service[TargetsConfig]
        clients <- ZIO.service[ClientRegistry]
        authClient <- make(client, targets, clients, LoadgenHttpClient.requestTimeout)
      yield authClient
