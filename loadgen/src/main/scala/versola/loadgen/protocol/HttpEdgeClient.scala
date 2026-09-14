package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.{Duration, IO, ZIO, ZLayer}

/** The edge origin's four web-session endpoints, decoded once.
  *
  * Same reason as [[AuthEndpoints]]: `URL.decode` per request is a parse and an allocation on
  * the hot path, and a base URL that does not parse is a configuration fault that has to surface
  * when the client is built rather than from inside a fiber (§3.2). The two preset-scoped paths
  * keep only their prefix here, since the preset id is a per-call segment.
  */
private case class EdgeEndpoints(
    login: URL,
    complete: URL,
    logout: URL,
    frontChannelLogout: URL,
)

private object EdgeEndpoints:
  def from(edgeUrl: String): Either[ProtocolError, EdgeEndpoints] =
    def at(path: String): Either[ProtocolError, URL] =
      URL.decode(edgeUrl + path).left.map(error => ProtocolError.Misconfigured(edgeUrl + path + ": " + error.getMessage))

    for
      login <- at("/login")
      complete <- at("/complete")
      logout <- at("/logout")
      frontChannel <- at("/logout/frontchannel")
    yield EdgeEndpoints(login, complete, logout, frontChannel)

/** [[EdgeClient]] against the real edge: §8.4's web/cookie login, and the logout that ends it.
  *
  * No response on this path carries a JSON body -- every hop is a redirect, and the payload the
  * flow is after is a `Location` or a `Set-Cookie` -- so there is nothing here to decode and no
  * `Json.Obj` to avoid. `HttpExchange` reads each body exactly once regardless, including on the
  * error paths, which is what §3.3 asks for; the strings are empty.
  *
  * [[call]] is delegated to the [[ActionClient]] track B already wrote rather than reimplemented:
  * §8.6's 401/403/step-up classification is identical whichever credential the call carried, and
  * a second copy of it is a second place for the outcome-versus-failure split to drift.
  */
final class HttpEdgeClient(exchange: HttpExchange, endpoints: EdgeEndpoints, actions: ActionClient) extends EdgeClient:
  import HttpEdgeClient.*

  override def call(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome] =
    actions.call(credential, action)

  override def login(preset: PresetId, acrValues: Option[List[String]]): IO[ProtocolError, EdgeLoginStarted] =
    val url = endpoints.login.copy(path = endpoints.login.path / preset.value)
    val request = Request.get(acrValues.fold(url)(values => url.addQueryParam(acrValuesParam, values.mkString(" "))))
    exchange.send(request).flatMap: received =>
      // A preset edge does not know is a 404, and it is the emulator's own fault: the campaign
      // named a preset its provisioning run never created (§10), so every web session for the
      // rest of the run would fail the same way. Not an outcome the scenario engine branches on.
      if received.status == Status.NotFound then ZIO.fail(ProtocolError.Misconfigured(unknownPreset + preset.value))
      else if !HttpExchange.isRedirect(received.status) then ZIO.fail(HttpExchange.unexpected(expectedRedirect, received.status, loginEndpoint))
      else
        for
          location <- HttpExchange.required(received.location, loginEndpoint, "redirect without a Location header")
          state <- HttpExchange.required(
            HttpExchange.redirectParam(location, stateParam),
            loginEndpoint,
            "no state on the authorize URL edge redirected to",
          )
        yield EdgeLoginStarted(location, state)

  override def startConversation(started: EdgeLoginStarted): IO[ProtocolError, ConversationCookie] =
    for
      url <- ZIO
        .fromEither(URL.decode(started.authorizeUrl))
        .mapError(error => ProtocolError.MalformedResponse(loginEndpoint, error.getMessage))
      received <- exchange.send(Request.get(url))
      conversation <-
        if HttpExchange.isRedirect(received.status) || received.status == Status.Ok then
          HttpExchange
            .required(
              HttpExchange.setCookie(received.response, HttpAuthClient.conversationCookie),
              authorizeEndpoint,
              "no " + HttpAuthClient.conversationCookie + " cookie on the /authorize response",
            )
            .map(cookie => ConversationCookie(cookie.content))
        else ZIO.fail(HttpExchange.unexpected(expectedConversation, received.status, authorizeEndpoint))
    yield conversation

  override def complete(state: String, code: AuthCode): IO[ProtocolError, EdgeCookie] =
    val url = endpoints.complete.addQueryParams(List(codeParam -> code.value, stateParam -> state))
    exchange.send(Request.get(url)).flatMap: received =>
      if !HttpExchange.isRedirect(received.status) then
        ZIO.fail(HttpExchange.unexpected(expectedRedirect, received.status, completeEndpoint))
      else
        HttpExchange
          .required(
            HttpExchange.setCookie(received.response, EdgeActionClient.edgeSessionCookie),
            completeEndpoint,
            "redirect without the " + EdgeActionClient.edgeSessionCookie + " cookie",
          )
          .map(EdgeCookie.of)

  override def completeError(state: String, error: String): IO[ProtocolError, Unit] =
    val url = endpoints.complete.addQueryParam(errorParam, error).addQueryParam(stateParam, state)
    exchange.send(Request.get(url)).flatMap: received =>
      // A redirect is the record consumed and the app told; a 400 is `AuthConversationNotFound`,
      // meaning there was nothing left to consume. Both satisfy the only reason for this hop, so
      // neither is a failure -- the refusal the caller is about to report is the outcome.
      if HttpExchange.isRedirect(received.status) || received.status == Status.BadRequest then ZIO.unit
      else ZIO.fail(HttpExchange.unexpected(expectedCompleteError, received.status, completeEndpoint))

  override def logout(preset: PresetId, session: EdgeSession): IO[ProtocolError, String] =
    val url = endpoints.logout.copy(path = endpoints.logout.path / preset.value)
    val request = Request.get(url).addHeader(HttpExchange.cookieHeader(EdgeActionClient.edgeSessionCookie, session.value))
    exchange.send(request).flatMap: received =>
      if HttpExchange.isRedirect(received.status) then
        HttpExchange.required(received.location, logoutEndpoint, "redirect without a Location header")
      else ZIO.fail(HttpExchange.unexpected(expectedRedirect, received.status, logoutEndpoint))

  override def endSession(session: EdgeSession): IO[ProtocolError, Unit] =
    val request = Request
      .get(endpoints.frontChannelLogout)
      .addHeader(HttpExchange.cookieHeader(EdgeActionClient.edgeSessionCookie, session.value))
    exchange.send(request).flatMap: received =>
      // Edge answers `200` and clears the cookie whether or not it found a session to revoke: an
      // already-expired cookie is not a failure to log out, it is a session that was already
      // gone. Nothing here reads the cleared cookie back -- the driver drops the row either way.
      if received.status == Status.Ok then ZIO.unit
      else ZIO.fail(HttpExchange.unexpected(expectedOk, received.status, frontChannelLogoutEndpoint))

object HttpEdgeClient:
  private val acrValuesParam = "acr_values"
  private val stateParam = "state"
  private val codeParam = "code"
  private val errorParam = "error"

  private val unknownPreset = "edge has no login preset "

  private val loginEndpoint = "/login/{presetId}"
  private val authorizeEndpoint = "/authorize"
  private val completeEndpoint = "/complete"
  private val logoutEndpoint = "/logout/{presetId}"
  private val frontChannelLogoutEndpoint = "/logout/frontchannel"

  private val expectedOk: Set[Status] = Set(Status.Ok)
  private val expectedRedirect: Set[Status] = Set(Status.SeeOther)
  private val expectedCompleteError: Set[Status] = Set(Status.SeeOther, Status.BadRequest)

  /** Auth answers the authorize hop with a redirect to `/challenge` in the ordinary case, and
    * the conversation cookie is what this hop is for -- but a deployment that renders the first
    * challenge page inline answers `200` with the same cookie, and either is a started
    * conversation. Anything else is not.
    */
  private val expectedConversation: Set[Status] = Set(Status.Ok, Status.SeeOther)

  def make(client: Client, targets: TargetsConfig, actions: ActionClient, requestTimeout: Duration): IO[ProtocolError, EdgeClient] =
    ZIO
      .fromEither(EdgeEndpoints.from(targets.edgeUrl))
      .map(endpoints => HttpEdgeClient(HttpExchange(client, requestTimeout), endpoints, actions))

  /** One client per driver pod, built once and shared by every fiber (§4). Takes the
    * [[ActionClient]] from the environment rather than building a second one, so both halves of
    * a web session's traffic go through one connection pool.
    */
  val live: ZLayer[Client & TargetsConfig & ActionClient, ProtocolError, EdgeClient] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        targets <- ZIO.service[TargetsConfig]
        actions <- ZIO.service[ActionClient]
        edgeClient <- make(client, targets, actions, LoadgenHttpClient.requestTimeout)
      yield edgeClient
