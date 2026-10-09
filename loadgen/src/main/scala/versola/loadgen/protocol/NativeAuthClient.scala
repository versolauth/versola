package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.loadgen.metrics.{LoadgenMetrics, TokenObserver}
import zio.http.*
import zio.json.*
import zio.{Duration, IO, Ref, UIO, ZIO}

/** `/native/start`'s answer, with the fields the driver uses (#420). */
private case class NativeStartBody(
    client_id: String,
    request_uri: String,
    state: String,
    blob: String,
    token_endpoint: String,
    revocation_endpoint: String,
) derives JsonDecoder

private case class MetadataAliases(token_endpoint: Option[String], revocation_endpoint: Option[String]) derives JsonDecoder

private case class MetadataBody(mtls_endpoint_aliases: Option[MetadataAliases]) derives JsonDecoder

/** The two `htu` values a device signs its later proofs for: auth's mutual-TLS aliases, which is
  * where edge forwards `/native/complete`, `/native/token` and `/native/revoke`.
  */
private case class NativeEndpoints(token: String, revocation: String)

/** What `/native/complete` needs back alongside the code: the `state` and the sealed blob
  * `/native/start` handed out.
  *
  * Carried in the [[CodeVerifier]] slot of [[AuthorizeStarted]] and [[AuthorizeOutcome.Authorized]],
  * which is where [[AuthClient.exchangeCode]] takes what must accompany the code. Native has no
  * verifier on the device -- edge holds the PKCE pair, sealed in the blob -- so the slot is free,
  * and keeping it there leaves [[MobileFlows]] and the scenario engine ignorant of which transport
  * a campaign drives. Both parts are base64url, so a `.` separates them unambiguously.
  */
private object NativeGrant:
  def encode(state: String, blob: String): CodeVerifier = CodeVerifier(state + "." + blob)

  def decode(verifier: CodeVerifier): Either[ProtocolError, (String, String)] =
    verifier.value.split("\\.", 2) match
      case Array(state, blob) => Right((state, blob))
      case _ => Left(ProtocolError.Misconfigured("a native code exchange was handed a code verifier, not a native grant"))

/** [[AuthClient]] for a mobile client under FAPI 2.0, which cannot be public: the authorization
  * request is pushed and the code redeemed by **edge**, authenticating as the client over mTLS,
  * while the device holds only the DPoP key (#420, #424).
  *
  * Everything that is not the client's own protocol -- the challenge pages, the credential
  * submits, RP-initiated logout -- is auth's directly, exactly as for a browser, and is delegated
  * to the [[HttpAuthClient]] this wraps. What changes is the three hops that carry a client
  * credential:
  *
  * ```
  * POST {edge}/native/start/{clientId}     (DPoP proof by the device key)  authorize
  * GET  {auth}/authorize?client_id&request_uri   ... conversation ...      authorize
  * POST {edge}/native/complete/{clientId}  (code, state, iss, blob + proof) token-code
  * POST {edge}/native/token/{clientId}     (refresh_token + proof)         token-refresh
  * ```
  *
  * so [[MobileFlows]], [[RefreshDiscipline]] and the scenario engine drive it unchanged. A DPoP
  * key is mandatory: it is the only sender constraint the flow has, so a run without one is a
  * configuration fault ([[ProtocolError.Misconfigured]]) and not a bearer fallback.
  *
  * Two nonces, because there are two verifiers. `start`'s proof is addressed to edge and judged
  * by edge; every later proof is forwarded byte for byte, and its nonce is auth's.
  */
final class NativeAuthClient(
    auth: HttpAuthClient,
    exchange: HttpExchange,
    edgeUrl: String,
    issuer: String,
    discovery: URL,
    clients: ClientRegistry,
    endpoints: Ref[Option[NativeEndpoints]],
    edgeNonce: Ref[Option[String]],
    authNonce: Ref[Option[String]],
    nativeBase: URL,
) extends AuthClient:
  import NativeAuthClient.*

  export auth.{
    challenge,
    submitPhone,
    submitOtp,
    submitPassword,
    submitSetPassword,
    submitLoginPassword,
    passkeyOptions,
    submitPasskeyAssertion,
    logout,
    logoutConfirmation,
    confirmLogout,
  }

  override def authorize(
      scope: String,
      clientId: Option[String],
      acrValues: Option[List[String]],
      sessionCookie: Option[SsoSession],
      key: Option[DpopKey],
  ): IO[ProtocolError, AuthorizeOutcome] =
    for
      registration <- ZIO.fromEither(clients.resolve(clientId))
      id = registration.creds.clientId
      signing <- requireKey(key)
      fields = List("scope" -> scope) ++ acrValues.map(values => "acr_values" -> values.mkString(" "))
      received <- signed(nativeUrl("start", id), edgeUrl + "/native/start/" + id, fields, signing, edgeNonce)
      started <-
        if received.status == Status.Ok then
          ZIO
            .fromEither(received.body.fromJson[NativeStartBody])
            .mapError(error => ProtocolError.MalformedResponse(startEndpoint, error))
        else ZIO.fail(proofRejection(received, startEndpoint).getOrElse(HttpExchange.unexpected(expectedOk, received.status, startEndpoint)))
      _ <- endpoints.set(Some(NativeEndpoints(started.token_endpoint, started.revocation_endpoint)))
      hop <- auth.authorizePushed(id, started.request_uri, sessionCookie)
      grant = NativeGrant.encode(started.state, started.blob)
      outcome <- hop match
        case Left(conversation) => ZIO.succeed(AuthorizeOutcome.Started(AuthorizeStarted(conversation, grant, started.state)))
        case Right(redirect) =>
          verifyCallback(started.state, redirect.state, redirect.iss).as(AuthorizeOutcome.Authorized(redirect.code, grant))
    yield outcome

  override def checkCallback(started: AuthorizeStarted, completed: ConversationCompleted): IO[ProtocolError, Unit] =
    verifyCallback(started.state, completed.state, completed.iss)

  /** What a native app does with the redirect before it presents the code: the `state` has to be
    * the one `/native/start` returned and the `iss` (RFC 9207) the issuer the request was pushed
    * to. Checked here, not left to edge's `/native/complete`, because the values sent on are the
    * ones `/native/start` and the configuration supplied -- so a redirect that named others would
    * otherwise be rewritten into a valid request and recorded as a flow that succeeded, which a
    * real app would have refused.
    */
  private def verifyCallback(expectedState: String, state: Option[String], iss: Option[String]): IO[ProtocolError, Unit] =
    if !state.contains(expectedState) then
      ZIO.fail(ProtocolError.MalformedResponse(authorizeEndpoint, "the callback's state is not the one /native/start returned"))
    else if !iss.exists(_.stripSuffix("/") == issuer) then
      ZIO.fail(ProtocolError.MalformedResponse(authorizeEndpoint, "the callback's iss is not the authorization server the request was pushed to"))
    else ZIO.unit

  override def exchangeCode(
      code: AuthCode,
      verifier: CodeVerifier,
      client: ClientCreds,
      key: Option[DpopKey],
  ): IO[ProtocolError, Tokens] =
    for
      signing <- requireKey(key)
      grant <- ZIO.fromEither(NativeGrant.decode(verifier))
      (state, blob) = grant
      resolved <- tokenEndpoint
      fields = List("code" -> code.value, "state" -> state, "iss" -> issuer, "blob" -> blob)
      received <- signed(nativeUrl("complete", client.clientId), resolved, fields, signing, authNonce)
      tokens <-
        if received.status == Status.Ok then decodeTokens(received.body, client.clientId)
        else ZIO.fail(proofRejection(received, completeEndpoint).getOrElse(HttpExchange.unexpected(expectedOk, received.status, completeEndpoint)))
    yield tokens

  override def exchangeRefresh(token: RefreshToken, client: ClientCreds, key: Option[DpopKey]): IO[ProtocolError, Tokens] =
    for
      signing <- requireKey(key)
      resolved <- tokenEndpoint
      received <- signed(nativeUrl("token", client.clientId), resolved, List("refresh_token" -> token.value), signing, authNonce)
      tokens <-
        if received.status == Status.Ok then decodeTokens(received.body, client.clientId)
        // The same split as `HttpAuthClient.exchangeRefresh`: a refusal of the driver's own proof
        // is an emulator fault and must not land in `loadgen_refresh_rejected_total`.
        else if proofRejection(received, refreshEndpoint).isDefined then ZIO.fail(proofRejection(received, refreshEndpoint).get)
        else if received.status == Status.BadRequest then ZIO.fail(ProtocolError.RefreshRejected(HttpAuthClient.refreshRejection(received)))
        else if received.status == Status.Unauthorized || received.status == Status.NotFound then
          ZIO.fail(ProtocolError.Misconfigured("edge rejected the native client on refresh"))
        else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, refreshEndpoint))
    yield tokens

  private def requireKey(key: Option[DpopKey]): IO[ProtocolError, DpopKey] =
    ZIO.fromOption(key).orElseFail(ProtocolError.Misconfigured(
      "a native client is sender-constrained by the device's DPoP key: configure the `dpop` block",
    ))

  private def nativeUrl(endpoint: String, clientId: String): URL =
    nativeBase.copy(path = nativeBase.path / endpoint / clientId)

  /** The `htu` of `/native/complete` and `/native/token`: what `/native/start` last said, or --
    * for a session resumed after a restart, which refreshes without ever starting -- what auth's
    * metadata publishes as its mutual-TLS alias. Cached either way; edge compares against its
    * configured `native.external-url`, which is the alias's origin.
    */
  private def tokenEndpoint: IO[ProtocolError, String] =
    endpoints.get.flatMap:
      case Some(known) => ZIO.succeed(known.token)
      case None =>
        exchange.send(Request.get(discovery)).flatMap: received =>
          if received.status != Status.Ok then ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, discoveryEndpoint))
          else
            ZIO
              .fromEither(received.body.fromJson[MetadataBody])
              .mapError(error => ProtocolError.MalformedResponse(discoveryEndpoint, error))
              .flatMap: metadata =>
                (for
                  aliases <- metadata.mtls_endpoint_aliases
                  token <- aliases.token_endpoint
                yield NativeEndpoints(token, aliases.revocation_endpoint.getOrElse(token))) match
                  case Some(found) => endpoints.set(Some(found)).as(found.token)
                  case None => ZIO.fail(ProtocolError.Misconfigured(
                    "auth's metadata publishes no mtls_endpoint_aliases, which edge's native endpoints sign proofs for",
                  ))

  /** One signed call, with RFC 9449 §9's nonce handshake around it; see
    * `HttpAuthClient.sendToken`, whose shape this has, down to the single retry.
    */
  private def signed(
      url: URL,
      htu: String,
      fields: List[(String, String)],
      key: DpopKey,
      nonce: Ref[Option[String]],
  ): IO[ProtocolError, Received] =
    def attempt(held: Option[String]): IO[ProtocolError, Received] =
      key.proof(Method.POST, htu, None, held).flatMap: proof =>
        exchange.send(
          Request
            .post(url, HttpExchange.formBody(fields))
            .addHeader(HttpExchange.formContentType)
            .addHeader(Header.Custom(HttpAuthClient.dpopHeader, proof)),
        )
    for
      held <- nonce.get
      first <- attempt(held)
      received <- nonceChallenge(first) match
        case None => adopt(nonce, first).as(first)
        case Some(issued) =>
          for
            _ <- nonce.set(Some(issued))
            _ <- LoadgenMetrics.dpopNonceRetried
            second <- attempt(Some(issued))
            _ <- adopt(nonce, second)
          yield second
    yield received

  private def decodeTokens(body: String, clientId: String): IO[ProtocolError, Tokens] =
    ZIO
      .fromEither(body.fromJson[TokenResponseBody])
      .mapError(error => ProtocolError.MalformedResponse(completeEndpoint, error))
      .tap(raw => ZIO.succeed(TokenObserver.record(clientId, raw.expires_in, raw.token_type)))
      .map: raw =>
        Tokens(AccessToken(raw.access_token), raw.refresh_token.map(RefreshToken.apply), raw.id_token.map(IdToken.apply), raw.expires_in)

object NativeAuthClient:
  private val startEndpoint = "/native/start/{clientId}"
  private val authorizeEndpoint = "/authorize"
  private val completeEndpoint = "/native/complete/{clientId}"
  private val refreshEndpoint = "/native/token/{clientId}"
  private val discoveryEndpoint = "/.well-known/openid-configuration"

  private val expectedOk: Set[Status] = Set(Status.Ok)

  private def adopt(nonce: Ref[Option[String]], received: Received): UIO[Unit] =
    received.response.rawHeader(HttpAuthClient.dpopNonceHeader) match
      case Some(issued) => nonce.set(Some(issued))
      case None => ZIO.unit

  private def errorCode(received: Received): Option[String] =
    received.body.fromJson[TokenErrorBody].toOption.map(_.error)

  /** `use_dpop_nonce`, from whoever answered: edge answers its own with `401`, auth's relayed
    * with `400` (`NativeController.render`, RFC 9449 §8), and either carries the nonce to retry
    * with in `DPoP-Nonce`.
    */
  private def nonceChallenge(received: Received): Option[String] =
    if received.status != Status.BadRequest && received.status != Status.Unauthorized then None
    else
      val challenged = errorCode(received).contains(HttpAuthClient.useDpopNonce) ||
        received.response.rawHeader("WWW-Authenticate").exists(_.contains(HttpAuthClient.useDpopNonce))
      if challenged then received.response.rawHeader(HttpAuthClient.dpopNonceHeader) else None

  /** A refusal of the driver's own proof, as `HttpAuthClient.proofRejection` reads one: an
    * emulator fault, never a measurement of the SUT. A `use_dpop_nonce` that survived the retry
    * counts too -- it is the server demanding the nonce it just issued.
    */
  private def proofRejection(received: Received, endpoint: String): Option[ProtocolError] =
    if received.status != Status.BadRequest && received.status != Status.Unauthorized then None
    else
      errorCode(received).collect:
        case HttpAuthClient.invalidDpopProof => ProtocolError.Misconfigured(s"the driver's DPoP proof was rejected at $endpoint")
        case HttpAuthClient.useDpopNonce => ProtocolError.Misconfigured(s"a DPoP nonce was demanded at $endpoint that had just been issued")

  /** Built once per driver, over the [[HttpAuthClient]] it delegates the conversation to. The
    * issuer is `targets.auth-url`, the same assumption `HttpAuthClient` makes for DPoP's `htu`
    * and the one edge's RFC 9207 check (`iss` against `native.issuer`) holds it to.
    */
  def make(
      client: Client,
      targets: TargetsConfig,
      clients: ClientRegistry,
      auth: HttpAuthClient,
      requestTimeout: Duration,
  ): IO[ProtocolError, AuthClient] =
    def decode(raw: String): IO[ProtocolError, URL] =
      ZIO.fromEither(URL.decode(raw)).mapError(error => ProtocolError.Misconfigured(raw + ": " + error.getMessage))
    for
      base <- decode(targets.edgeUrl + "/native")
      discovery <- decode(targets.authUrl + discoveryEndpoint)
      endpoints <- Ref.make(Option.empty[NativeEndpoints])
      edgeNonce <- Ref.make(Option.empty[String])
      authNonce <- Ref.make(Option.empty[String])
    yield NativeAuthClient(
      auth,
      HttpExchange(client, requestTimeout),
      targets.edgeUrl.stripSuffix("/"),
      targets.authUrl.stripSuffix("/"),
      discovery,
      clients,
      endpoints,
      edgeNonce,
      authNonce,
      base,
    )
