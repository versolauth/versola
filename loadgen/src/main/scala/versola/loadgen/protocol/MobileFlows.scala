package versola.loadgen.protocol

import zio.{IO, ZIO}

import java.util.UUID

/** Everything one `/authorize` varies by. `acrValues` and `sessionCookie` are what turn a login
  * into a step-up on an existing SSO session (§7.4) rather than a fresh one.
  */
case class LoginRequest(
    clientId: Option[String],
    scope: String,
    acrValues: Option[List[String]],
    sessionCookie: Option[SsoSession],
)

/** The mobile flows of versola-loadgen-dev-spec.md §8.1-8.3, §8.5 and §8.6, driven over
  * [[AuthClient]]/[[ActionClient]].
  *
  * The three login flows are one state machine, not three transcripts: the hop sequences in §8
  * differ only in what the SUT renders at each step, and the SUT -- not the driver -- decides
  * that, from the client's provisioned auth flow and the tenant's challenge settings. That
  * machine is [[ChallengeConversation]], which §8.4's web flow walks as well.
  *
  * `origin` is the WebAuthn `rp.origin` the software authenticator signs against (§5's
  * `targets.origin`); `otpCode` is derived once from the provisioned OTP length (§7.4 --
  * six digits is a default, not a constant).
  */
final class MobileFlows(
    auth: AuthClient,
    actions: ActionClient,
    clients: ClientRegistry,
    observer: FlowObserver,
    otpCode: String,
    origin: String,
):
  private val conversation = ChallengeConversation(auth, observer, otpCode, origin)

  /** §8.1 */
  def mobileOtp(request: LoginRequest, phone: String): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    login(FlowName.MobileOtp, request, Credentials.PhoneOtp(phone))

  /** §8.2 -- the Argon2 path, tagged as its own flow so its latency is separable. */
  def mobileOtpPassword(request: LoginRequest, phone: String, password: String): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    login(FlowName.MobileOtpPassword, request, Credentials.PhoneOtpPassword(phone, password))

  /** §8.3 */
  def mobilePasskey(
      request: LoginRequest,
      credential: SoftAuthenticator.Credential,
      sutUserId: UUID,
  ): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    login(FlowName.MobilePasskey, request, Credentials.Passkey(credential, sutUserId))

  /** §7.4's step-up: the same conversation as a login, re-run with `acr_values` on the SSO
    * session the session already holds, so auth asks only for the factor the requested assurance
    * level is missing rather than starting again at credentials.
    *
    * Its own [[FlowName]] and not a variant of the login it reuses, because §7.4 requires the
    * step-up to be recorded as its own scenario -- folding its latency into `mobile-otp` would
    * make the full-login histogram a mixture of two flows whose hop counts differ, and hide the
    * ~1.7x full-login rate design doc §2.3 says these run at.
    *
    * The caller supplies `request.sessionCookie` and `request.acrValues`; without the first this
    * degrades into an ordinary full login that happens to be labelled a step-up, which is the
    * scenario mix being misreported rather than a failure anything would catch.
    */
  def stepUp(request: LoginRequest, credentials: Credentials): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    login(FlowName.StepUp, request, credentials)

  /** §8.5. One hop, but still a flow of its own: it is the single most frequent thing the
    * campaign does, and `RefreshRejected` must stay at ~0 for the whole run (§7.4).
    *
    * The caller is responsible for the two rules around this call that this client cannot
    * enforce (design doc §6.3): at most one in-flight operation per virtual user, and the new
    * refresh token persisted before it is used.
    */
  def refresh(token: RefreshToken, client: ClientCreds): IO[ProtocolError, Tokens] =
    FlowTiming.flow(observer, FlowName.Refresh):
      FlowTiming.step(observer, FlowName.Refresh, StepName.TokenRefresh)(auth.exchangeRefresh(token, client))

  /** §8.6 */
  def businessAction(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome] =
    FlowTiming.flow(observer, FlowName.BusinessAction):
      FlowTiming.step(observer, FlowName.BusinessAction, StepName.Action)(actions.call(credential, action))

  def logout(idToken: IdToken): IO[ProtocolError, Unit] =
    FlowTiming.flow(observer, FlowName.Logout):
      FlowTiming.step(observer, FlowName.Logout, StepName.Logout)(auth.logout(idToken))

  private def login(
      flow: FlowName,
      request: LoginRequest,
      credentials: Credentials,
  ): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    FlowTiming.flow(observer, flow):
      for
        registration <- ZIO.fromEither(clients.resolve(request.clientId))
        outcome <- FlowTiming.step(observer, flow, StepName.Authorize):
          auth.authorize(request.scope, request.clientId, request.acrValues, request.sessionCookie)
        result <- outcome match
          case AuthorizeOutcome.Started(started) =>
            for
              completed <- conversation.walk(flow, credentials, started.conversation).flatMap(ChallengeConversation.orFail)
              tokens <- FlowTiming.step(observer, flow, StepName.TokenCode):
                auth.exchangeCode(completed.code, started.codeVerifier, registration.creds)
            yield (tokens, completed.ssoSession)
          case AuthorizeOutcome.Authorized(code, codeVerifier) =>
            FlowTiming.step(observer, flow, StepName.TokenCode)(auth.exchangeCode(code, codeVerifier, registration.creds))
              .map(tokens => (tokens, request.sessionCookie))
      yield result
