package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.loadgen.metrics.LoadgenMetrics
import zio.http.*
import zio.http.Header.Authorization
import zio.{Duration, IO, Ref, UIO, ZIO, ZLayer}

/** The business-action half of §8.6: a call through the edge's resource proxy, with either the
  * mobile bearer token or the web `EDGE_SESSION` cookie.
  *
  * Deliberately not an [[EdgeClient]] implementation. §8.4's `/login/{presetId}` ->
  * `/complete` -> cookie path is new code that belongs to track G (#276); this is the one call
  * §8.1-8.3 and §8.5 need to exercise a session once it exists, and track G composes it rather
  * than re-deriving the 401/403 handling below.
  */
final class EdgeActionClient(exchange: HttpExchange, resources: URL, nonce: Ref[Option[String]]) extends ActionClient:
  import EdgeActionClient.*

  override def call(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome] =
    val url = resources.copy(path = Path.decode(action.path))
    send(credential, action, url).flatMap(outcome(_, action, credential))

  private def request(action: ActionCall, url: URL): Request =
    val request = Request(
      method = action.method,
      url = url,
      body = action.body.fold(Body.empty)(Body.fromString(_)),
    )
    action.body.fold(request)(_ => request.addHeader(jsonContentType))

  /** One action, with RFC 9449 §9's nonce handshake around it on the sender-constrained path.
    *
    * Not an edge case the way it is at `/token`, where a client's `require_dpop_nonce` is off
    * unless a tenant turns it on: a registered edge requires a nonce by default
    * (`V1024__edges_require_dpop_nonce.sql`). Without the handshake every action is answered
    * `use_dpop_nonce`, which carries no `acr_values` and so reads as an expired token -- the
    * session refreshes, retries, is refused again, and not one business call reaches the
    * resource while the campaign records a plausible-looking action rate.
    *
    * The nonce is edge's, minted from a salt of its own with no reference to who is asking, so
    * one is held per driver and not per session: the first action of a driver's life pays the
    * extra round trip and every later one carries the cached value. Edge's nonce and auth's are
    * separate secrets, hence a cache here rather than one shared with [[HttpAuthClient]].
    *
    * Exactly one retry, for that client's reason: a second challenge to a proof carrying the
    * nonce edge has just issued is the SUT contradicting itself, and a driver that kept retrying
    * would spin for the rest of the campaign.
    */
  private def send(credential: EdgeCredential, action: ActionCall, url: URL): IO[ProtocolError, Received] =
    credential match
      case dpop: EdgeCredential.Dpop =>
        for
          held <- nonce.get
          first <- attempt(dpop, action, url, held)
          received <- nonceChallenge(first) match
            case None => adoptNonce(first).as(first)
            case Some(issued) =>
              for
                _ <- nonce.set(Some(issued))
                _ <- LoadgenMetrics.dpopNonceRetried
                second <- attempt(dpop, action, url, Some(issued))
                _ <- adoptNonce(second)
              yield second
        yield received
      case unbound => exchange.send(authenticate(request(action, url), unbound))

  private def attempt(
      credential: EdgeCredential.Dpop,
      action: ActionCall,
      url: URL,
      nonce: Option[String],
  ): IO[ProtocolError, Received] =
    sign(request(action, url), credential, action.method, url, nonce).flatMap(exchange.send)

  /** §8: edge may hand back a fresh nonce on any response, a successful one included, and expects
    * the next proof to carry it. Adopting it here is what holds the steady state at one round
    * trip per action across a rotation.
    */
  private def adoptNonce(received: Received): UIO[Unit] =
    received.response.rawHeader(HttpAuthClient.dpopNonceHeader) match
      case Some(issued) => nonce.set(Some(issued))
      case None => ZIO.unit

  /** §9's challenge, and the nonce it carries to retry with. `None` for every other `401`,
    * including the step-up demand and an expired token, which share its status.
    */
  private def nonceChallenge(received: Received): Option[String] =
    if received.status != Status.Unauthorized then None
    else if !dpopChallenge(received).contains(useDpopNonce) then None
    else received.response.rawHeader(HttpAuthClient.dpopNonceHeader)

  /** Edge picks the path off the `Authorization` scheme, so `DPoP` is not a bearer call carrying
    * an extra header -- the scheme is what makes it read the proof at all (`DpopVerifier.Scheme`).
    *
    * `htu` comes from the URL this request is actually addressed to, minus its query, which is
    * what edge reconstructs from its own configured `edgeUrl` and the request path -- the same
    * caveat `HttpAuthClient.tokenHtu` carries, and the same reason a mismatch surfaces as the
    * emulator's fault.
    *
    * `ath` is mandatory here and absent at `/token`: §7 requires a resource proof to name the
    * token it accompanies, and edge refuses one without it (`DpopVerifier.Error.AthMissing`).
    */
  private def authenticate(request: Request, credential: EdgeCredential): Request =
    credential match
      case EdgeCredential.Bearer(token) => request.addHeader(Authorization.Bearer(token.value))
      case EdgeCredential.Cookie(session) => request.addHeader(HttpExchange.cookieHeader(edgeSessionCookie, session.value))
      case EdgeCredential.Dpop(_, _) => request

  private def sign(
      request: Request,
      credential: EdgeCredential.Dpop,
      method: Method,
      url: URL,
      nonce: Option[String],
  ): IO[ProtocolError, Request] =
    credential.key
      .proof(method, url.copy(queryParams = QueryParams.empty, fragment = None).encode, Some(credential.token), nonce)
      .map: proof =>
        request
          .addHeader(Header.Custom(Authorization.name, s"$dpopScheme ${credential.token.value}"))
          .addHeader(Header.Custom(HttpAuthClient.dpopHeader, proof))

  /** The three outcomes that are outcomes and not failures (§4): a step-up demand, a `403` a
    * `retail-basic` user is expected to collect, and an expired access token. Each is a branch
    * the scenario engine takes, and none of them touches the error budget.
    *
    * Anything else that isn't a `2xx` success -- a `404`/`500` from edge or the upstream
    * `mockapi`, say -- is not a fourth outcome: it has no scenario-engine branch and no error
    * this client can attribute to a documented cause, so it fails as `UnexpectedStatus` rather
    * than being recorded as a normal `ActionOutcome`.
    */
  private def outcome(received: Received, action: ActionCall, credential: EdgeCredential): IO[ProtocolError, ActionOutcome] =
    if received.status == Status.Forbidden then ZIO.fail(ProtocolError.Forbidden(action.path))
    else if received.status == Status.Unauthorized then
      // RFC 9449 §7.1's two refusals share the status an expired token gets, and both are the
      // emulator's fault rather than the SUT's, so they are read off before either branch below.
      // A driver that took them for expiry would refresh a perfectly live token, present the same
      // bad proof again, and spend the campaign in a loop it reported as ordinary traffic.
      dpopChallenge(received) match
        case Some(`invalidDpopProof`) =>
          ZIO.fail(ProtocolError.Misconfigured(s"edge rejected the driver's DPoP proof at ${action.path}"))
        case Some(`useDpopNonce`) =>
          ZIO.fail(ProtocolError.Misconfigured(s"edge demanded a DPoP nonce at ${action.path} that it had just issued"))
        case _ =>
          stepUpAcrValues(received) match
            case Some(acrValues) => ZIO.fail(ProtocolError.StepUpRequired(acrValues, action.path))
            case None => ZIO.fail(ProtocolError.Unauthorized(action.path))
    else if received.status.isSuccess then
      // Edge rotates EDGE_SESSION whenever it refreshes behind the cookie; a caller that does
      // not adopt the new value loses the session mid-run and reads it as an SUT failure (§8.4).
      // Only the success path has a session to rotate -- an error response has no reason to
      // carry one, and applying this on that path would not be a decision.
      val rotated = credential match
        case EdgeCredential.Cookie(_) => HttpExchange.setCookie(received.response, edgeSessionCookie).map(EdgeCookie.of)
        case EdgeCredential.Bearer(_) | EdgeCredential.Dpop(_, _) => None
      ZIO.succeed(ActionOutcome(received.status, received.body, rotated))
    else ZIO.fail(HttpExchange.unexpected(expectedSuccess, received.status, action.path))

object EdgeActionClient:
  private[protocol] val edgeSessionCookie = "EDGE_SESSION"

  /** RFC 9449 §7.1's authorization scheme, which replaces `Bearer` on a sender-constrained call
    * rather than accompanying it.
    */
  private val dpopScheme = "DPoP"
  private val jsonContentType = Header.ContentType(MediaType.application.json)
  private val expectedSuccess: Set[Status] = Set(Status.Ok)

  private val insufficientAuthentication = "insufficient_user_authentication"
  private val acrValuesParameter = java.util.regex.Pattern.compile("""acr_values="([^"]*)"""")

  private val invalidDpopProof = HttpAuthClient.invalidDpopProof
  private val useDpopNonce = HttpAuthClient.useDpopNonce

  /** Edge refuses a proof with `401` and `WWW-Authenticate: DPoP error="..."` (`EdgeService.proxy`)
    * -- the same status and header a step-up demand uses under the `Bearer` scheme. The scheme is
    * the discriminator, so the pattern is anchored on it: a `Bearer` challenge that happened to
    * carry an `error` parameter must not be read as a refused proof.
    */
  private val dpopErrorParameter = java.util.regex.Pattern.compile("""^DPoP\s+error="([^"]*)"""")

  private def dpopChallenge(received: Received): Option[String] =
    received.response.rawHeader("WWW-Authenticate").flatMap: challenge =>
      val matcher = dpopErrorParameter.matcher(challenge)
      if matcher.find() then Option(matcher.group(1)) else None

  /** Edge answers a step-up demand with `401` and
    * `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="..."`
    * (`EdgeService.proxy`). The `acr_values` parameter is space-delimited, as everywhere else in
    * OAuth. A plain `401` without that error is an expired token, not a step-up -- the two drive
    * completely different branches, so the discriminator is the error code, not the status.
    */
  private def stepUpAcrValues(received: Received): Option[List[String]] =
    received.response
      .rawHeader("WWW-Authenticate")
      .filter(_.contains(insufficientAuthentication))
      .map: challenge =>
        val matcher = acrValuesParameter.matcher(challenge)
        if matcher.find() then matcher.group(1).split(' ').iterator.filter(_.nonEmpty).toList else Nil

  def make(client: Client, targets: TargetsConfig, requestTimeout: Duration): IO[ProtocolError, ActionClient] =
    at(client, targets.edgeUrl, requestTimeout)

  /** The same client against a base URL that is not edge's.
    *
    * The one caller is the calibration gate (versolauth/versola#281), which drives `mockapi`
    * directly with the SUT out of the picture. It is this client and not a second one on purpose:
    * what the gate calibrates is the instrument the campaign measures the SUT with, so every hop
    * of the request -- the pooled `ZClient`, the per-request timeout, the body read -- has to be
    * the one a campaign actually uses. The three outcome branches below are simply never taken
    * against `mockapi`, whose contract is "always 200 after a delay".
    */
  def at(client: Client, baseUrl: String, requestTimeout: Duration): IO[ProtocolError, ActionClient] =
    for
      url <- ZIO
        .fromEither(URL.decode(baseUrl).left.map(error => ProtocolError.Misconfigured(baseUrl + ": " + error.getMessage)))
      nonce <- Ref.make(Option.empty[String])
    yield EdgeActionClient(HttpExchange(client, requestTimeout), url, nonce)

  val live: ZLayer[Client & TargetsConfig, ProtocolError, ActionClient] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        targets <- ZIO.service[TargetsConfig]
        actionClient <- make(client, targets, LoadgenHttpClient.requestTimeout)
      yield actionClient
