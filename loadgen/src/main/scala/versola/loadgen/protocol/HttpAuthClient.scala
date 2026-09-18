package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.loadgen.metrics.{LoadgenMetrics, TokenObserver}
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*
import zio.{Duration, IO, Ref, UIO, ZIO, ZLayer}

/** The `/token` response, with only the fields a driver uses. A `derives JsonDecoder` case
  * class rather than the e2e client's `Json.Obj`: the AST costs one allocation per field per
  * response, on the one endpoint every single session hits (§3.2).
  *
  * `token_type` is `Option` despite being REQUIRED by RFC 6749 §5.1, because the report states
  * the mode the run was driven in against the mode the SUT answered in, and a decode that failed
  * on an omission would turn that finding into a dead campaign.
  */
private case class TokenResponseBody(
    token_type: Option[String],
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
final class HttpAuthClient(
    exchange: HttpExchange,
    endpoints: AuthEndpoints,
    clients: ClientRegistry,
    nonce: Ref[Option[String]],
) extends AuthClient:
  import HttpAuthClient.*

  /** RFC 9449 §4.3 step 9 compares the proof's `htu` against the endpoint's own URI, and auth
    * derives that from its configured issuer rather than from the inbound request, so that a
    * forwarded host header cannot make a proof minted for another origin validate there
    * (`TokenEndpointController.tokenEndpointUri`).
    *
    * So this is the endpoint the driver dialled, which is correct only while `targets.auth-url`
    * is the issuer auth publishes. Pointing a driver at an internal service address whose issuer
    * is the public one refuses every proof -- which is why that refusal is
    * [[ProtocolError.Misconfigured]] below and not an SUT error: it is a whole campaign
    * measuring nothing, and it should read that way on the first request rather than as a 100%
    * error budget.
    */
  private val tokenHtu = endpoints.token.copy(queryParams = QueryParams.empty, fragment = None).encode

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
          ZIO.succeed(AuthorizeOutcome.Started(AuthorizeStarted(ConversationCookie(cookie.content), pkce.verifier, state)))
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

  override def exchangeCode(
      code: AuthCode,
      verifier: CodeVerifier,
      client: ClientCreds,
      key: Option[DpopKey],
  ): IO[ProtocolError, Tokens] =
    for
      registration <- ZIO.fromEither(clients.resolve(Some(client.clientId)))
      fields = List(
        "grant_type" -> "authorization_code",
        "code" -> code.value,
        "redirect_uri" -> registration.redirectUri,
        "code_verifier" -> verifier.value,
      )
      received <- sendToken(fields, client, key)
      tokens <-
        if received.status == Status.Ok then decodeTokens(received.body, client.clientId)
        else ZIO.fail(proofRejection(received).getOrElse(HttpExchange.unexpected(expectedOk, received.status, tokenEndpoint)))
    yield tokens

  override def exchangeRefresh(token: RefreshToken, client: ClientCreds, key: Option[DpopKey]): IO[ProtocolError, Tokens] =
    sendToken(List("grant_type" -> "refresh_token", "refresh_token" -> token.value), client, key)
      .flatMap: received =>
        if received.status == Status.Ok then decodeTokens(received.body, client.clientId)
        // Checked before the 400 below, not after. RFC 9449 gives `invalid_dpop_proof` and a
        // surviving `use_dpop_nonce` the same status as a rejected grant, so both would land in
        // `loadgen_refresh_rejected_total` -- the one counter §7.4 requires to stay at ~0, and
        // whose non-zero value is read as either a scheduling bug or a real SUT defect. A driver
        // whose own proofs are wrong would condemn the SUT on this line.
        else if proofRejection(received).isDefined then ZIO.fail(proofRejection(received).get)
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

  override def logoutConfirmation(location: String, ssoSession: SsoSession): IO[ProtocolError, LogoutConfirmation] =
    for
      url <- ZIO
        .fromEither(URL.decode(location))
        .mapError(error => ProtocolError.MalformedResponse(logoutEndpoint, error.getMessage))
      received <- exchange.send(Request.get(url).addHeader(HttpExchange.cookieHeader(ssoSessionCookie, ssoSession.value)))
      confirmation <-
        if received.status != Status.Ok then ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, logoutEndpoint))
        else
          // No token means auth found no session to confirm away and rendered the signed-out
          // page instead. Typed rather than tolerated: submitting without one is a 403, and a
          // driver that treated the render as a logout would report one that never happened.
          HttpExchange
            .required(ChallengePage.csrfOf(received.body), logoutEndpoint, "confirmation page without a csrf token")
            .map(csrf =>
              LogoutConfirmation(
                url,
                csrf,
                url.queryParams.getAll(postLogoutRedirectUriParam).headOption,
                url.queryParams.getAll(stateParam).headOption,
              ),
            )
    yield confirmation

  override def confirmLogout(ssoSession: SsoSession, confirmation: LogoutConfirmation): IO[ProtocolError, Unit] =
    val fields = List(csrfField -> confirmation.csrf.value) ++
      confirmation.postLogoutRedirectUri.map(postLogoutRedirectUriParam -> _) ++
      confirmation.state.map(stateParam -> _)
    val request = Request
      .post(confirmation.url, HttpExchange.formBody(fields))
      .addHeader(HttpExchange.cookieHeader(ssoSessionCookie, ssoSession.value))
      .addHeader(HttpExchange.formContentType)
    exchange.send(request).flatMap: received =>
      // The signed-out page, or a redirect to the post-logout URI when one was posted back.
      if received.status == Status.Ok || HttpExchange.isRedirect(received.status) then ZIO.unit
      // A 403 is the confirmation not matching -- the session is still live, so this is the one
      // status here that must not read as a completed logout.
      else if received.status == Status.Forbidden then
        ZIO.fail(ProtocolError.MalformedResponse(logoutEndpoint, "confirmation rejected, the session is still live"))
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
          .map: location =>
            SubmitOutcome.Redirected(location, HttpExchange.setCookie(received.response, ssoSessionCookie).map(cookie => SsoSession(cookie.content)))
      else if received.status == Status.Ok then ZIO.succeed(SubmitOutcome.Rendered(ChallengePage.parse(conversation, received.body)))
      else ZIO.fail(HttpExchange.unexpected(expectedSubmit, received.status, endpoint))
    }

  /** A confidential client authenticates with HTTP Basic; a public one (the three `mobile-*`
    * clients of design doc §2.2 hold no secret) names itself in the body and proves itself with
    * PKCE instead.
    */
  private def tokenRequest(fields: List[(String, String)], client: ClientCreds, proof: Option[String]): Request =
    val request = Request
      .post(endpoints.token, HttpExchange.formBody(client.clientSecret.fold(fields :+ ("client_id" -> client.clientId))(_ => fields)))
      .addHeader(HttpExchange.formContentType)
    val authenticated = client.clientSecret.fold(request)(secret => request.addHeader(Authorization.Basic(client.clientId, secret)))
    proof.fold(authenticated)(value => authenticated.addHeader(Header.Custom(dpopHeader, value)))

  /** One token call, with RFC 9449 §9's nonce handshake around it when a key is in play.
    *
    * The nonce is held per driver process rather than per session, because it is the server's
    * and not the session's: auth mints it from a secret of its own, with no reference to who is
    * asking. Sharing it is what keeps the handshake off the hot path -- the first call of a
    * driver's life pays the extra round trip and every later one carries the cached value, so a
    * deployment with `require-dpop-nonce` on costs one retry per driver rather than one per
    * token call.
    *
    * Exactly one retry. A second challenge to a proof carrying the nonce auth has just issued is
    * the SUT contradicting itself, and a driver that kept retrying would spin for the rest of
    * the campaign instead of failing one step.
    */
  private def sendToken(
      fields: List[(String, String)],
      client: ClientCreds,
      key: Option[DpopKey],
  ): IO[ProtocolError, Received] =
    key match
      case None => exchange.send(tokenRequest(fields, client, None))
      case Some(signing) =>
        for
          held <- nonce.get
          proof <- signing.proof(Method.POST, tokenHtu, None, held)
          first <- exchange.send(tokenRequest(fields, client, Some(proof)))
          received <- nonceChallenge(first) match
            case None => adoptNonce(first).as(first)
            case Some(issued) =>
              for
                _ <- nonce.set(Some(issued))
                _ <- LoadgenMetrics.dpopNonceRetried
                retried <- signing.proof(Method.POST, tokenHtu, None, Some(issued))
                second <- exchange.send(tokenRequest(fields, client, Some(retried)))
                _ <- adoptNonce(second)
              yield second
        yield received

  /** §8: the server may hand back a fresh nonce on any response, a successful one included, and
    * expects the next proof to carry it. Adopting it here is what holds the steady state at one
    * round trip per token call across a rotation.
    */
  private def adoptNonce(received: Received): UIO[Unit] =
    received.response.rawHeader(dpopNonceHeader) match
      case Some(issued) => nonce.set(Some(issued))
      case None => ZIO.unit

  /** The `use_dpop_nonce` challenge of §9, and the nonce it carries to retry with. `None` for
    * every other response, including a `400` that is an ordinary grant rejection.
    */
  private def nonceChallenge(received: Received): Option[String] =
    if received.status != Status.BadRequest then None
    else
      received.body.fromJson[TokenErrorBody].toOption
        .filter(_.error == useDpopNonce)
        .flatMap(_ => received.response.rawHeader(dpopNonceHeader))

  /** A refusal of the driver's own proof rather than of the grant it accompanied.
    *
    * [[ProtocolError.Misconfigured]] for the reason that constructor gives: every occurrence is a
    * campaign measuring something other than what it claims. A wrong `htu`, a clock outside
    * auth's `iat` leeway, or a key the token was not bound to are all emulator faults, and all
    * three would otherwise be indistinguishable from the SUT rejecting a legitimate request.
    *
    * A `use_dpop_nonce` reaching here has already survived one retry, so it is no longer a
    * handshake -- it is auth demanding a nonce it just issued.
    */
  private def proofRejection(received: Received): Option[ProtocolError] =
    if received.status != Status.BadRequest then None
    else
      received.body.fromJson[TokenErrorBody].toOption.map(_.error).collect:
        case `invalidDpopProof` =>
          ProtocolError.Misconfigured(s"auth rejected the driver's DPoP proof at $tokenEndpoint")
        case `useDpopNonce` =>
          ProtocolError.Misconfigured(s"auth demanded a DPoP nonce at $tokenEndpoint that it had just issued")

  private def decodeTokens(body: String, clientId: String): IO[ProtocolError, Tokens] =
    ZIO
      .fromEither(body.fromJson[TokenResponseBody])
      .mapError(error => ProtocolError.MalformedResponse(tokenEndpoint, error))
      .tap(raw => ZIO.succeed(TokenObserver.record(clientId, raw.expires_in, raw.token_type)))
      .map: raw =>
        Tokens(
          AccessToken(raw.access_token),
          raw.refresh_token.map(RefreshToken.apply),
          raw.id_token.map(IdToken.apply),
          raw.expires_in,
        )

object HttpAuthClient:
  private[protocol] val conversationCookie = "SSO_CONVERSATION"
  private[protocol] val ssoSessionCookie = "SSO_SESSION"

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

  /** RFC 9449 §4.1 and §8: the header a proof travels in, and the one a nonce comes back in.
    * Spelled here rather than taken from `versola.util.Dpop`, which names neither -- it verifies
    * a proof it has already been handed.
    */
  private[protocol] val dpopHeader = "DPoP"
  private[protocol] val dpopNonceHeader = "DPoP-Nonce"

  // RFC 9449 §5 and §9's two `error` codes. At `/token` they arrive as a 400 alongside the grant
  // rejections that share that status; at edge's resource proxy, in a `WWW-Authenticate` on a 401.
  private[protocol] val invalidDpopProof = "invalid_dpop_proof"
  private[protocol] val useDpopNonce = "use_dpop_nonce"

  // The confirmation posts back the two parameters auth bound its token to, under the names
  // `LogoutController`'s form decoder reads.
  private val csrfField = "csrf_token"
  private val postLogoutRedirectUriParam = "post_logout_redirect_uri"
  private val stateParam = "state"

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
    for
      endpoints <- ZIO.fromEither(AuthEndpoints.from(targets.authUrl))
      nonce <- Ref.make(Option.empty[String])
    yield HttpAuthClient(HttpExchange(client, requestTimeout), endpoints, clients, nonce)

  /** One client per driver pod, built once and shared by every fiber (§4). */
  val live: ZLayer[Client & TargetsConfig & ClientRegistry, ProtocolError, AuthClient] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        targets <- ZIO.service[TargetsConfig]
        clients <- ZIO.service[ClientRegistry]
        authClient <- make(client, targets, clients, LoadgenHttpClient.requestTimeout)
      yield authClient
