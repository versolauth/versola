package versola.loadgen.protocol

import zio.json.*
import zio.{Clock, IO, ZIO}

import java.util.UUID

/** What a virtual user can present at the credential step. Which one it holds is what makes it
  * a `mobile-otp`, `mobile-otp-password` or `mobile-passkey` user (design doc §2.2).
  */
enum Credentials:
  case PhoneOtp(phone: String)
  case PhoneOtpPassword(phone: String, password: String)
  case Passkey(credential: SoftAuthenticator.Credential, sutUserId: UUID)

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
  * that, from the client's provisioned auth flow and the tenant's challenge settings. Driving
  * them from the `versola-step` meta tag means a challenge-settings change (an extra password
  * step, say) shows up as a different measured sequence rather than as a fake protocol failure.
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
  import MobileFlows.*

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

  /** §8.5. One hop, but still a flow of its own: it is the single most frequent thing the
    * campaign does, and `RefreshRejected` must stay at ~0 for the whole run (§7.4).
    *
    * The caller is responsible for the two rules around this call that this client cannot
    * enforce (design doc §6.3): at most one in-flight operation per virtual user, and the new
    * refresh token persisted before it is used.
    */
  def refresh(token: RefreshToken, client: ClientCreds): IO[ProtocolError, Tokens] =
    timedFlow(FlowName.Refresh):
      timedStep(FlowName.Refresh, StepName.TokenRefresh)(auth.exchangeRefresh(token, client))

  /** §8.6 */
  def businessAction(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome] =
    timedFlow(FlowName.BusinessAction):
      timedStep(FlowName.BusinessAction, StepName.Action)(actions.call(credential, action))

  def logout(idToken: IdToken): IO[ProtocolError, Unit] =
    timedFlow(FlowName.Logout):
      timedStep(FlowName.Logout, StepName.Logout)(auth.logout(idToken))

  private def login(
      flow: FlowName,
      request: LoginRequest,
      credentials: Credentials,
  ): IO[ProtocolError, (Tokens, Option[SsoSession])] =
    timedFlow(flow):
      for
        registration <- ZIO.fromEither(clients.resolve(request.clientId))
        outcome <- timedStep(flow, StepName.Authorize):
          auth.authorize(request.scope, request.clientId, request.acrValues, request.sessionCookie)
        result <- outcome match
          case AuthorizeOutcome.Started(started) =>
            for
              (code, ssoSession) <- converse(flow, credentials, started.conversation, None, maxSteps)
              tokens <- timedStep(flow, StepName.TokenCode)(auth.exchangeCode(code, started.codeVerifier, registration.creds))
            yield (tokens, ssoSession)
          case AuthorizeOutcome.Authorized(code, codeVerifier) =>
            timedStep(flow, StepName.TokenCode)(auth.exchangeCode(code, codeVerifier, registration.creds))
              .map(tokens => (tokens, request.sessionCookie))
      yield result

  /** Walks the conversation until it redirects to the code. `page` is the one already in hand
    * when a submit answered `200` with the next step rendered inline instead of redirecting
    * back to `/challenge`; `None` means fetch it.
    */
  private def converse(
      flow: FlowName,
      credentials: Credentials,
      conversation: ConversationCookie,
      page: Option[ChallengePage],
      remaining: Int,
  ): IO[ProtocolError, (AuthCode, Option[SsoSession])] =
    if remaining <= 0 then ZIO.fail(ProtocolError.MalformedResponse(challengeEndpoint, "conversation never reached the code redirect"))
    else
      for
        current <- page.fold(timedStep(flow, StepName.Challenge)(auth.challenge(conversation)))(ZIO.succeed)
        csrf <- HttpExchange.required(current.csrf, challengeEndpoint, "no csrf token in the rendered form")
        step <- HttpExchange.required(current.step, challengeEndpoint, "no versola-step meta tag on the page")
        outcome <- submit(flow, credentials, conversation, step, csrf)
        result <- outcome match
          case SubmitOutcome.Rendered(next) => converse(flow, credentials, conversation, Some(next), remaining - 1)
          case SubmitOutcome.Redirected(location, ssoSession) => follow(flow, credentials, conversation, location, ssoSession, remaining)
      yield result

  private def follow(
      flow: FlowName,
      credentials: Credentials,
      conversation: ConversationCookie,
      location: String,
      ssoSession: Option[SsoSession],
      remaining: Int,
  ): IO[ProtocolError, (AuthCode, Option[SsoSession])] =
    HttpExchange.redirectParam(location, "code") match
      case Some(code) => ZIO.succeed((AuthCode(code), ssoSession))
      case None =>
        HttpExchange.redirectParam(location, "error") match
          // The SUT refused the authorization outright (`access_denied`, `login_required`, ...).
          // Not a malformed page in the literal sense, but it is the conversation ending in a
          // way the flow cannot continue from, and the `error` code is what a report needs.
          case Some(error) => ZIO.fail(ProtocolError.MalformedResponse(authorizeEndpoint, error))
          case None => converse(flow, credentials, conversation, None, remaining - 1)

  private def submit(
      flow: FlowName,
      credentials: Credentials,
      conversation: ConversationCookie,
      step: ConversationStep,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome] =
    step match
      case ConversationStep.Credential =>
        credentials match
          case Credentials.PhoneOtp(phone) => timedStep(flow, StepName.SubmitPhone)(auth.submitPhone(conversation, phone, csrf))
          case Credentials.PhoneOtpPassword(phone, _) => timedStep(flow, StepName.SubmitPhone)(auth.submitPhone(conversation, phone, csrf))
          case Credentials.Passkey(credential, sutUserId) => assertPasskey(flow, conversation, credential, sutUserId, csrf)
      case ConversationStep.Otp =>
        timedStep(flow, StepName.SubmitOtp)(auth.submitOtp(conversation, otpCode, csrf))
      case ConversationStep.Password =>
        credentials match
          case Credentials.PhoneOtpPassword(_, password) =>
            timedStep(flow, StepName.SubmitPassword)(auth.submitPassword(conversation, password, csrf))
          case _ => ZIO.fail(ProtocolError.Misconfigured("the SUT asked for a password this virtual user was not provisioned with"))
      case other =>
        ZIO.fail(ProtocolError.MalformedResponse(challengeEndpoint, other.value))

  /** The signing itself is local work and not a hop, so it sits between the two measured steps
    * rather than inside either: folding it into `passkey-options` would charge the SUT for the
    * emulator's ECDSA.
    */
  private def assertPasskey(
      flow: FlowName,
      conversation: ConversationCookie,
      credential: SoftAuthenticator.Credential,
      sutUserId: UUID,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome] =
    for
      options <- timedStep(flow, StepName.PasskeyOptions)(auth.passkeyOptions(conversation))
      assertion <- SoftAuthenticator.get(credential, options, origin, sutUserId)
      outcome <- timedStep(flow, StepName.SubmitPasskey)(auth.submitPasskeyAssertion(conversation, assertion.toJson, csrf))
    yield outcome

  private def timedStep[A](flow: FlowName, step: StepName)(effect: IO[ProtocolError, A]): IO[ProtocolError, A] =
    for
      start <- Clock.nanoTime
      result <- effect.either
      end <- Clock.nanoTime
      _ <- observer.step(flow, step, end - start, result.left.toOption)
      value <- ZIO.fromEither(result)
    yield value

  private def timedFlow[A](flow: FlowName)(effect: IO[ProtocolError, A]): IO[ProtocolError, A] =
    for
      start <- Clock.nanoTime
      result <- effect.either
      end <- Clock.nanoTime
      _ <- observer.flow(flow, end - start, result.left.toOption)
      value <- ZIO.fromEither(result)
    yield value

object MobileFlows:
  private val challengeEndpoint = "/challenge"
  private val authorizeEndpoint = "/authorize"

  /** A conversation that has not produced a code after this many pages is stuck. The longest
    * flow in §8 is phone + OTP + password at three submits and four pages; the bound exists so
    * that an unexpected SUT loop costs one typed failure instead of a fiber that never returns.
    */
  private val maxSteps = 8
