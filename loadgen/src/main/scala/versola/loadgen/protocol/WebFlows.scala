package versola.loadgen.protocol

import zio.{IO, ZIO}

/** What one web login varies by. Narrower than [[LoginRequest]] on purpose: a web login is
  * started by edge, from a preset, so there is no `client_id` and no `scope` for the driver to
  * choose -- both are the preset's.
  *
  * `ssoSession` survives the narrowing because it is a cookie and not a query parameter: edge
  * builds the authorize URL, but the driver makes the request to it, so it can send a session it
  * already holds even though it cannot name one on the URL. `None` is a login by someone who
  * holds no session; a step-up passes the one the row persisted.
  */
case class WebLoginRequest(preset: PresetId, acrValues: Option[List[String]], ssoSession: Option[SsoSession])

/** §8.4: the web client `web-otp` authenticating **through** edge and ending in a cookie
  * session, plus §8.6 and the logout for that session.
  *
  * The hop sequence, each hop its own measured step and the whole thing measured end to end:
  *
  * ```
  * GET  {edge}/login/{presetId}          -> 303 {auth}/authorize?...   edge-login
  * GET  {auth}/authorize?...             -> 303 /challenge, SSO_CONVERSATION   authorize
  * ... §8.1 hops 2-5, via ChallengeConversation ...    -> 303 {edge}/complete?code=&state=
  * GET  {edge}/complete?code=&state=     -> 303 app, Set-Cookie EDGE_SESSION   edge-complete
  * ```
  *
  * The middle is [[ChallengeConversation]], shared with §8.1-8.3: the pages, the submits and the
  * order are auth's, and auth does not know or care that edge started this one. What is new is
  * the two edge hops around it and the fact that the flow ends in a cookie rather than a token
  * -- every other flow in §8 ends in a bearer token.
  */
final class WebFlows(
    edge: EdgeClient,
    auth: AuthClient,
    observer: FlowObserver,
    otpCode: String,
    origin: String,
):
  import WebFlows.*

  private val conversation = ChallengeConversation(auth, observer, otpCode, origin)

  /** §8.4 end to end. Returns the cookie edge issued and the `SSO_SESSION` auth left behind on
    * the way through -- both belong in the session's `vu_sessions` row (see
    * `DeviceSession.webCookie`), the cookie because it *is* the credential and the SSO session
    * because it is the only thing that survives the cookie.
    */
  def webOtp(request: WebLoginRequest, credentials: Credentials): IO[ProtocolError, (EdgeCookie, Option[SsoSession])] =
    login(FlowName.WebOtp, request, credentials)

  /** §7.4's step-up on the cookie path. The same hops as [[webOtp]], with `acr_values` on edge's
    * `/login` -- which is the only way a web login can ask for an assurance level, since edge and
    * not the driver builds the authorize URL -- and reported as its own flow for the reason §7.4
    * gives: a step-up's latency does not belong in the action's, nor in the login's.
    *
    * It ends in a new `EDGE_SESSION` rather than in a token pair, so the caller has a cookie to
    * adopt as well as an assurance level to persist.
    *
    * `request.ssoSession` is what makes this a step-up rather than a login wearing the name: auth
    * recognises the session behind the authorize hop and asks only for the factor the requested
    * ACR is missing. Called with `None` it still succeeds -- and measures a full credential
    * conversation as a step-up, which inflates the flow's latency and understates the login's.
    */
  def stepUp(request: WebLoginRequest, credentials: Credentials): IO[ProtocolError, (EdgeCookie, Option[SsoSession])] =
    login(FlowName.WebStepUp, request, credentials)

  private def login(
      flow: FlowName,
      request: WebLoginRequest,
      credentials: Credentials,
  ): IO[ProtocolError, (EdgeCookie, Option[SsoSession])] =
    FlowTiming.flow(observer, flow):
      for
        started <- FlowTiming.step(observer, flow, StepName.EdgeLogin)(edge.login(request.preset, request.acrValues))
        conversationCookie <- FlowTiming.step(observer, flow, StepName.Authorize)(edge.startConversation(started, request.ssoSession))
        outcome <- conversation.walk(flow, credentials, conversationCookie)
        completed <- refusalCompleted(flow, outcome)
        state <- echoedState(started, completed)
        cookie <- FlowTiming.step(observer, flow, StepName.EdgeComplete)(edge.complete(state, completed.code))
      yield (cookie, completed.ssoSession)

  /** §8.6 on the web path: the same proxied action the mobile flows make, with the cookie in
    * place of the bearer token, and with §8.4's adoption rule discharged rather than documented.
    *
    * Returns the session to use for the *next* call, which is the rotated one whenever edge
    * refreshed behind the cookie. A caller that ignored a rotation would lose the session
    * mid-run and read it as a phantom SUT failure, so the type does not offer that option --
    * this is the one place in §8.4 where getting it wrong is silent.
    *
    * A `401` still arrives as [[ProtocolError.Unauthorized]], which is a *planned* outcome and
    * not a failure: on the cookie path it means edge could not refresh either, so the session is
    * finished and the caller re-runs [[webOtp]]. Turning it into a success value here would take
    * it out of the taxonomy that counts it.
    *
    * The rotation is a two-phase step like §7.4's refresh -- the SUT has moved the session on
    * before the row knows -- but it needs no generation column to be recoverable, and the
    * difference is in what a superseded cookie is. Edge's cookie carries the access token
    * itself, and edge rotates only once that token has expired, so the value a crash leaves in
    * the row is one whose token is already dead and whose refresh has already been spent. A
    * driver that resumes on it cannot succeed, cannot rotate again, and cannot provoke the reuse
    * detection a replayed *refresh* token would: it gets one `401`, counted as the planned
    * outcome above, and re-runs [[webOtp]]. One wasted action per interrupted session is the
    * whole blast radius, which is why [[DeviceSessionRepository.storeEdgeCookie]] guards nothing
    * and takes no expected generation.
    */
  def businessAction(session: EdgeSession, action: ActionCall): IO[ProtocolError, (ActionOutcome, EdgeSession)] =
    FlowTiming.flow(observer, FlowName.BusinessAction):
      FlowTiming
        .step(observer, FlowName.BusinessAction, StepName.Action)(edge.call(EdgeCredential.Cookie(session), action))
        .map(outcome => (outcome, outcome.rotatedSession.fold(session)(_.session)))

  /** A web logout, which is four hops: edge hands the browser to auth, auth renders a
    * confirmation, the confirmation is submitted, and only then does the front-channel call the
    * signed-out page triggers revoke the session edge side. A browser makes all four, so the
    * driver makes all four.
    *
    * Each of the middle two is load-bearing. Edge's redirect carries no `id_token_hint` -- it
    * keeps the id token -- so auth takes its `cookie` branch and renders rather than acts, and
    * "the session survives an unverified confirmation" (`LogoutController`). A driver that
    * stopped at edge would therefore leave the SSO session live, and the next [[webOtp]] for
    * that user would be satisfied silently: a full OTP conversation replaced by a short
    * reauthorization, which is the scenario mix being misreported rather than merely a session
    * outliving its logout.
    *
    * The `SSO_SESSION` is a parameter because auth identifies the session to end by that cookie
    * alone on this path, and it is the credential [[webOtp]] returns and the row persists
    * precisely so a later logout can present it.
    *
    * `endSession` comes last, not first: in a browser it is the OP's own signed-out page that
    * triggers it from an iframe, so making that call before auth has logged out reproduces the
    * effect without its cause.
    */
  def logout(request: WebLoginRequest, session: EdgeSession, ssoSession: SsoSession): IO[ProtocolError, Unit] =
    FlowTiming.flow(observer, FlowName.WebLogout):
      for
        authLogout <- FlowTiming.step(observer, FlowName.WebLogout, StepName.EdgeLogout)(edge.logout(request.preset, session))
        confirmation <- FlowTiming.step(observer, FlowName.WebLogout, StepName.AuthLogout)(auth.logoutConfirmation(authLogout, ssoSession))
        _ <- FlowTiming.step(observer, FlowName.WebLogout, StepName.AuthLogoutConfirm)(
          auth.confirmLogout(ssoSession, confirmation),
        )
        _ <- FlowTiming.step(observer, FlowName.WebLogout, StepName.EdgeEndSession)(edge.endSession(session))
      yield ()

  /** A refusal on the web path still owes edge a hop. Auth ends a refused web authorization at
    * `/complete?error=...&state=...`, and that branch is what consumes the `pending_logins`
    * record edge wrote when it started the login; a driver that stopped at the refusal would
    * leave one behind per refused login until its TTL, which is the emulator adding rows to the
    * system it is measuring.
    *
    * The hop is skipped when there is no `state` to name the record with, and its own failure is
    * not allowed to replace the refusal: what the report needs is the SUT's `error` code, not a
    * secondary complaint about the cleanup.
    */
  private def refusalCompleted(flow: FlowName, outcome: ConversationOutcome): IO[ProtocolError, ConversationCompleted] =
    outcome match
      case ConversationOutcome.Refused(error, Some(state)) =>
        FlowTiming
          .step(observer, flow, StepName.EdgeCompleteError)(edge.completeError(state, error))
          .ignore *> ChallengeConversation.orFail(outcome)
      case other => ChallengeConversation.orFail(other)

  /** The `state` on the redirect back must be the one edge minted, or `/complete` is about to
    * hand edge a code against another virtual user's pending login -- with many of them logging
    * in through one edge at once, that is a live risk, and edge cannot catch it: it only ever
    * sees the value the driver sends. Sending edge's own `state` instead of checking would make
    * the mismatch unobservable rather than absent.
    *
    * One string comparison per login, and the failure string is built only on the branch that
    * takes it (§3.2).
    */
  private def echoedState(started: EdgeLoginStarted, completed: ConversationCompleted): IO[ProtocolError, String] =
    HttpExchange
      .required(completed.state, completeEndpoint, "no state on the redirect back to edge")
      .flatMap: state =>
        if state == started.state then ZIO.succeed(state)
        else ZIO.fail(ProtocolError.MalformedResponse(completeEndpoint, "state " + state + " is not the one edge recorded"))

object WebFlows:
  private val completeEndpoint = "/complete"
