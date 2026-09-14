package versola.loadgen.protocol

import zio.json.*
import zio.{IO, ZIO}

import java.util.UUID

/** What a virtual user can present at the credential step. Which one it holds is what makes it
  * a `mobile-otp`, `mobile-otp-password`, `mobile-passkey` or `web-otp` user (design doc §2.2).
  */
enum Credentials:
  case PhoneOtp(phone: String)
  case PhoneOtpPassword(phone: String, password: String)
  case Passkey(credential: SoftAuthenticator.Credential, sutUserId: UUID)

/** How a conversation ended: the authorization code, the `state` the SUT put on the redirect
  * that carried it, and the `SSO_SESSION` the completed conversation left behind.
  *
  * `state` is `Option` because the redirect is the SUT's and this type does not get to require
  * anything of it; the caller that needs it says so. §8.4 is the caller that does -- it has
  * edge's own `state` to compare against, and a mismatch there means the login is about to be
  * completed against another virtual user's pending record.
  */
case class ConversationCompleted(code: AuthCode, state: Option[String], ssoSession: Option[SsoSession])

/** How a conversation ended. A refusal is reported rather than failed here because the two
  * flows owe the SUT different things afterwards: a mobile refusal redirects to the client's
  * own `redirect_uri` and there is nothing to clean up, while a web refusal redirects to edge's
  * `/complete`, whose error branch consumes the `pending_logins` record. Failing in the walker
  * would make that hop unreachable and leave a row in the SUT for every refused web login until
  * its TTL -- the emulator distorting the system it measures.
  *
  * Both flows end in the same typed failure regardless (see [[ChallengeConversation.orFail]]);
  * the difference is only whether a hop is made first.
  */
enum ConversationOutcome:
  case Completed(conversation: ConversationCompleted)
  case Refused(error: String, state: Option[String])

/** The `/challenge` conversation -- hops 2-5 of §8.1, which are also hops 3-6 of §8.4 -- walked
  * until it redirects out of the conversation with an authorization code.
  *
  * Shared by [[MobileFlows]] and [[WebFlows]] rather than written once in each: the two differ
  * only in who started the conversation and in where its final redirect points -- the client's
  * own `redirect_uri` for a mobile flow, edge's `/complete` for the web one -- and this looks at
  * neither. The step order is the SUT's decision, taken from the client's provisioned auth flow
  * and the tenant's challenge settings, so a challenge-settings change shows up as a different
  * measured sequence rather than as a fake protocol failure.
  *
  * `origin` is the WebAuthn `rp.origin` the software authenticator signs against (§5's
  * `targets.origin`); `otpCode` is derived once from the provisioned OTP length (§7.4 -- six
  * digits is a default, not a constant).
  */
private[protocol] final class ChallengeConversation(
    auth: AuthClient,
    observer: FlowObserver,
    otpCode: String,
    origin: String,
):
  import ChallengeConversation.*

  def walk(
      flow: FlowName,
      credentials: Credentials,
      conversation: ConversationCookie,
  ): IO[ProtocolError, ConversationOutcome] =
    converse(flow, credentials, conversation, None, maxSteps)

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
  ): IO[ProtocolError, ConversationOutcome] =
    if remaining <= 0 then ZIO.fail(ProtocolError.MalformedResponse(challengeEndpoint, "conversation never reached the code redirect"))
    else
      for
        current <- page.fold(FlowTiming.step(observer, flow, StepName.Challenge)(auth.challenge(conversation)))(ZIO.succeed)
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
  ): IO[ProtocolError, ConversationOutcome] =
    HttpExchange.redirectParam(location, "code") match
      case Some(code) =>
        ZIO.succeed(
          ConversationOutcome.Completed(ConversationCompleted(AuthCode(code), HttpExchange.redirectParam(location, stateParam), ssoSession)),
        )
      case None =>
        HttpExchange.redirectParam(location, "error") match
          // The SUT refused the authorization outright (`access_denied`, `login_required`, ...).
          // Not a malformed page in the literal sense, but it is the conversation ending in a
          // way the flow cannot continue from, and the `error` code is what a report needs.
          case Some(error) => ZIO.succeed(ConversationOutcome.Refused(error, HttpExchange.redirectParam(location, stateParam)))
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
          case Credentials.PhoneOtp(phone) =>
            FlowTiming.step(observer, flow, StepName.SubmitPhone)(auth.submitPhone(conversation, phone, csrf))
          case Credentials.PhoneOtpPassword(phone, _) =>
            FlowTiming.step(observer, flow, StepName.SubmitPhone)(auth.submitPhone(conversation, phone, csrf))
          case Credentials.Passkey(credential, sutUserId) => assertPasskey(flow, conversation, credential, sutUserId, csrf)
      case ConversationStep.Otp =>
        FlowTiming.step(observer, flow, StepName.SubmitOtp)(auth.submitOtp(conversation, otpCode, csrf))
      case ConversationStep.Password =>
        credentials match
          case Credentials.PhoneOtpPassword(_, password) =>
            FlowTiming.step(observer, flow, StepName.SubmitPassword)(auth.submitPassword(conversation, password, csrf))
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
      options <- FlowTiming.step(observer, flow, StepName.PasskeyOptions)(auth.passkeyOptions(conversation))
      assertion <- SoftAuthenticator.get(credential, options, origin, sutUserId)
      outcome <- FlowTiming.step(observer, flow, StepName.SubmitPasskey)(auth.submitPasskeyAssertion(conversation, assertion.toJson, csrf))
    yield outcome

private[protocol] object ChallengeConversation:
  private val challengeEndpoint = "/challenge"
  private val authorizeEndpoint = "/authorize"
  private val stateParam = "state"

  /** The refusal as a failure, which is what both flows ultimately report -- the same error on
    * the mobile path as before this outcome was made visible, so nothing downstream of it
    * changed.
    */
  def orFail(outcome: ConversationOutcome): IO[ProtocolError, ConversationCompleted] =
    outcome match
      case ConversationOutcome.Completed(conversation) => ZIO.succeed(conversation)
      case ConversationOutcome.Refused(error, _) => ZIO.fail(ProtocolError.MalformedResponse(authorizeEndpoint, error))

  /** A conversation that has not produced a code after this many pages is stuck. The longest
    * flow in §8 is phone + OTP + password at three submits and four pages; the bound exists so
    * that an unexpected SUT loop costs one typed failure instead of a fiber that never returns.
    */
  private val maxSteps = 8
