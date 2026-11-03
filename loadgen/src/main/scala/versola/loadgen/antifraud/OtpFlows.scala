package versola.loadgen.antifraud

import versola.loadgen.protocol.*
import zio.http.URL
import zio.{Clock, Duration, IO, UIO, ZIO}

/** The pieces of the phone sign-in a scenario is made of, each of the four calls antifraud has a view on timed on
  * its own: asking for a code (`otp-request`), entering it (`otp-verify`), and the two that it must leave alone,
  * the code exchange (`token`) and the refresh (`refresh`).
  *
  * @param measureOtp whether `otp-request` and `otp-verify` are recorded; off for the logins that only set up a
  *                   scenario that is about something else
  */
final class OtpFlows(
    auth: AuthClient,
    config: AntifraudConfig,
    stub: SmsStub,
    measurements: Measurements,
    tally: Tally,
    solver: PowSolver,
    measureOtp: Boolean = true,
):
  import OtpFlows.*

  private val creds = ClientCreds(config.clientId, None)

  /** Starts a conversation and gives a number to it, as the configured kind of client does (`otp.client`): a script client
    * answers a challenge with a proof of work and gets its code; a plain one is left pending and asks again after the
    * pause. Either way a challenge is counted as one (`challenged`), apart from a refusal, and paid for as work (`solved`)
    * or by waiting (`paidByWaiting`). */
  def requestOtp(phone: String): IO[ProtocolError, Requested] =
    val request =
      for
        outcome <- auth.authorize(scope = config.scope, clientId = Some(config.clientId), acrValues = None, sessionCookie = None, key = None)
        started <- outcome match
          case AuthorizeOutcome.Started(started) => ZIO.succeed(started)
          case AuthorizeOutcome.Authorized(_, _) =>
            ZIO.fail(ProtocolError.Misconfigured("/authorize answered with a code, not a conversation: the client has a session"))
        conversation = started.conversation
        page <- auth.challenge(conversation)
        csrf <- required(page.csrf, "/challenge", "no csrf token on the credential page")
        began <- Clock.nanoTime
        landed <- config.otp.client match
          case OtpClient.Script => viaScript(conversation, phone, csrf)
          case OtpClient.Plain => viaPlain(conversation, phone, csrf)
        ended <- Clock.nanoTime
        _ <- ZIO.succeed(measurements.challenge.recordTotal((ended - began) / 1000L)).when(measureOtp)
      yield landed match
        case Landed.Unanswered(reason) => (Requested.Other(s"challenge unanswered: $reason"): Requested, Paid.No)
        case Landed.Page(None, _, _) => (Requested.Refused, Paid.No)
        case Landed.Page(Some(next), paid, micros) =>
          next.step match
            case Some(ConversationStep.Otp) if next.otpPending => (Requested.Other("pending"), Paid.No)
            case Some(ConversationStep.Otp) => (Requested.Sent(started, next, micros), paid)
            case Some(ConversationStep.AccessDenied) => (Requested.Refused, Paid.No)
            case other => (Requested.Other(other.fold("none")(_.value)), Paid.No)
    ZIO.succeed(tally.requested.increment()) *> request
      .tapError(_ => ZIO.succeed(measurements.challenge.recordTotalFailed()).when(measureOtp))
      .tapBoth(
        error => ZIO.succeed(tally.fail(OtpFlows.reason(error))),
        {
          case (_: Requested.Sent, paid) =>
            ZIO.succeed:
              tally.sent.increment()
              paid match
                case Paid.Work => tally.solved.increment()
                case Paid.Waiting =>
                  tally.paidByWaiting.increment()
                  measurements.challenge.pauseHonoured.increment()
                case Paid.No => ()
          case (Requested.Refused, _) => ZIO.succeed(tally.refused.increment())
          case (_: Requested.Other, _) => ZIO.succeed(tally.other.increment())
        },
      )
      .map(_._1)

  /** The form sent as a page with script sends it (`proof_inline`), and every proof of work it is given solved and sent
    * back with the same request, up to `challenge.max-rounds` times in a row, as `inline.ts` does. The `otp-request`
    * sample is the time `auth` took over all the posts of the request: the solving is not in it (it is the client's
    * and is in [[ChallengeStats]]), so that it is comparable with a run that was never challenged. */
  private def viaScript(conversation: ConversationCookie, phone: String, csrf: Csrf): IO[ProtocolError, Landed] =
    def loop(round: Int, proof: Option[PowAnswer], server: Long, challenged: Boolean): IO[ProtocolError, (Landed, Long)] =
      for
        posted <- elapsed(auth.submitPhoneInline(conversation = conversation, phone = phone, csrf = csrf, proof = proof))
        (outcome, micros) = posted
        total = server + micros
        result <- outcome match
          case InlineOutcome.Page(page) => ZIO.succeed((Landed.Page(Some(page), if challenged then Paid.Work else Paid.No, total), total))
          case InlineOutcome.Step(step) =>
            step.challenge match
              case None =>
                step.next match
                  case Some(location) => landing(conversation, location, if challenged then Paid.Work else Paid.No, total).map(_ -> total)
                  case None => ZIO.fail(ProtocolError.MalformedResponse("/challenge/phone", "an inline answer with neither next nor challenge"))
              case Some(challenge) =>
                answer(challenge, round, challenged).flatMap:
                  case Left(reason) => ZIO.succeed((Landed.Unanswered(reason), total))
                  case Right(solved) => loop(round + 1, Some(solved), total, challenged = true)
      yield result
    loop(round = 0, proof = None, server = 0L, challenged = false)
      .tapBoth(
        _ => ZIO.succeed(measurements.failed("otp-request")).when(measureOtp),
        (_, micros) => ZIO.succeed(measurements.record("otp-request", micros)).when(measureOtp),
      )
      .map(_._1)

  /** What a challenge becomes: the proof that answers it, or the reason it is not answered. */
  private def answer(challenge: InlineChallenge, round: Int, already: Boolean): IO[ProtocolError, Either[String, PowAnswer]] =
    val stats = measurements.challenge
    val settings = config.challenge
    def unanswered(reason: String): UIO[Either[String, PowAnswer]] =
      ZIO.succeed:
        stats.unanswered.increment()
        Left(reason)
    val counted = ZIO.succeed {
      stats.received.increment()
      if !already then tally.challenged.increment()
    }
    counted *> (
      if challenge.kind != Pow.Kind then unanswered(s"kind ${challenge.kind}")
      else if round >= settings.maxRounds then unanswered(s"over ${settings.maxRounds} rounds")
      else
        (challenge.params.get("seed"), challenge.params.get("difficulty").flatMap(_.toIntOption)) match
          case (Some(seed), Some(difficulty)) =>
            if difficulty > settings.maxDifficulty then unanswered(s"difficulty $difficulty over ${settings.maxDifficulty}")
            else
              solver
                .solve(seed, difficulty)
                .flatMap:
                  case Some(found) =>
                    ZIO.succeed:
                      stats.recordSolve(found.micros, found.solution.hashes)
                      Right(PowAnswer(challenge.token, found.solution.nonce))
                  case None => unanswered("solving stopped")
          case _ => ZIO.fail(ProtocolError.MalformedResponse("/challenge/phone", "a pow challenge without a seed and a difficulty"))
    )

  /** The form posted as a plain one is, with no script. A challenged request is left on the code step with nothing sent;
    * the ask for the code is made again after the pause (see [[askAgain]]). */
  private def viaPlain(conversation: ConversationCookie, phone: String, csrf: Csrf): IO[ProtocolError, Landed] =
    for
      posted <- elapsed(auth.submitPhone(conversation = conversation, phone = phone, csrf = csrf)).tapBoth(
        _ => ZIO.succeed(measurements.failed("otp-request")).when(measureOtp),
        (_, micros) => ZIO.succeed(measurements.record("otp-request", micros)).when(measureOtp),
      )
      (submitted, micros) = posted
      landed <- landingOf(conversation, submitted, Paid.No, micros)
      done <- landed match
        case Landed.Page(Some(page), _, _) if page.otpPending =>
          val left = ZIO.succeed {
            tally.challenged.increment()
            measurements.challenge.paused.increment()
          }
          left *> askAgain(conversation, page, asks = 0, micros)
        case other => ZIO.succeed(other)
    yield done

  /** A pending request's ask for the code, made after the pause and, if `auth` answers with the same page (its pause was
    * not over, or it has none), again every `pause-slack` up to `challenge.max-asks` times. An ask made early is answered
    * with the same page and nothing else: no code, no error, not counted by `auth`. */
  private def askAgain(conversation: ConversationCookie, pending: ChallengePage, asks: Int, requestMicros: Long): IO[ProtocolError, Landed] =
    val settings = config.challenge
    for
      _ <- ZIO.sleep(if asks == 0 then settings.pause.plus(settings.pauseSlack) else settings.pauseSlack)
      csrf <- required(pending.csrf, "/challenge/otp/resend", "no csrf token on the pending code page")
      submitted <- timed("otp-resend", measureOtp)(auth.submitResend(conversation = conversation, csrf = csrf))
      landed <- landingOf(conversation, submitted, Paid.Waiting, requestMicros)
      result <- landed match
        case Landed.Page(Some(next), _, _) if next.otpPending =>
          if asks + 1 < settings.maxAsks then ZIO.succeed(measurements.challenge.earlyAsks.increment()) *> askAgain(conversation, next, asks + 1, requestMicros)
          else ZIO.succeed(landed)
        case other => ZIO.succeed(other)
    yield result

  private def landingOf(conversation: ConversationCookie, submitted: SubmitOutcome, paid: Paid, micros: Long): IO[ProtocolError, Landed] =
    submitted match
      case SubmitOutcome.Rendered(page) => ZIO.succeed(Landed.Page(Some(page), paid, micros))
      case SubmitOutcome.Redirected(location, _) => landing(conversation, location, paid, micros)

  /** A refusal is an error redirect, or the access-denied page the next fetch of the conversation renders. */
  private def landing(conversation: ConversationCookie, location: String, paid: Paid, micros: Long): IO[ProtocolError, Landed] =
    if param(location, "error").isDefined then ZIO.succeed(Landed.Page(None, Paid.No, micros))
    else auth.challenge(conversation).map(page => Landed.Page(Some(page), paid, micros))

  private def elapsed[A](call: IO[ProtocolError, A]): IO[ProtocolError, (A, Long)] =
    Clock.nanoTime.flatMap(start => call.flatMap(result => Clock.nanoTime.map(end => (result, (end - start) / 1000L))))

  /** Enters the code the number was sent. `None` when the code was not accepted. */
  def verify(sent: Requested.Sent, phone: String): IO[ProtocolError, Option[Granted]] =
    codeFor(phone).flatMap(enter(sent, _))

  /** Enters `code`, which need not be the right one. */
  def enter(sent: Requested.Sent, code: String): IO[ProtocolError, Option[Granted]] =
    val conversation = sent.started.conversation
    val verification =
      for
        csrf <- required(sent.page.csrf, "/challenge/otp", "no csrf token on the code page")
        submitted <- timed("otp-verify", measureOtp)(auth.submitOtp(conversation = conversation, code = code, csrf = csrf))
        granted <- submitted match
          case SubmitOutcome.Redirected(location, _) =>
            ZIO.succeed(param(location, "code").map(authCode => Granted(AuthCode(authCode), sent.started.codeVerifier)))
          case SubmitOutcome.Rendered(_) => ZIO.none
      yield granted
    verification.tapBoth(
      error => ZIO.succeed(tally.fail(OtpFlows.reason(error))),
      {
        case Some(_) => ZIO.succeed(tally.verified.increment())
        case None => ZIO.succeed(tally.rejected.increment())
      },
    )

  def exchange(granted: Granted): IO[ProtocolError, Tokens] =
    timed("token", true)(auth.exchangeCode(code = granted.code, verifier = granted.verifier, client = creds, key = None))

  def refresh(tokens: Tokens): IO[ProtocolError, Tokens] =
    ZIO
      .fromOption(tokens.refreshToken)
      .orElseFail(ProtocolError.Misconfigured(s"/token issued no refresh token: is offline_access in the scope '${config.scope}'?"))
      .flatMap(token => timed("refresh", true)(auth.exchangeRefresh(token = token, client = creds, key = None)))

  /** A full login that is not measured but for `/token`: what the control scenario starts from. */
  def login(phone: String): IO[ProtocolError, Option[Tokens]] =
    requestOtp(phone).flatMap:
      case sent: Requested.Sent => verify(sent, phone).flatMap(ZIO.foreach(_)(exchange))
      case _ => ZIO.none

  private def codeFor(phone: String): IO[ProtocolError, String] =
    config.otp.source match
      case OtpSource.Fixed => ZIO.succeed(Otp.nonProd(config.otp.length))
      case OtpSource.Stub =>
        ZIO
          .fromOption(stub.codeFor(phone))
          .orElse(ZIO.sleep(Duration.fromMillis(50)) *> ZIO.fromOption(stub.codeFor(phone)))
          .orElseFail(ProtocolError.Misconfigured(
            s"no code reached the SMS stub for $phone: is auth running with env = prod and otp-provider pointing at it?",
          ))

  private def timed[A](name: String, record: Boolean)(call: IO[ProtocolError, A]): IO[ProtocolError, A] =
    if !record then call
    else
      Clock.nanoTime.flatMap: start =>
        call.tapBoth(
          _ => ZIO.succeed(measurements.failed(name)),
          _ => Clock.nanoTime.map(end => measurements.record(name, (end - start) / 1000L)),
        )

object OtpFlows:
  /** A failure in a few words, to be counted by. */
  def reason(error: ProtocolError): String =
    error match
      case ProtocolError.Transport(cause) => s"transport: ${cause.getClass.getSimpleName}"
      case ProtocolError.UnexpectedStatus(_, got, endpoint) => s"status ${got.code} from $endpoint"
      case ProtocolError.MalformedResponse(endpoint, detail) => s"malformed answer from $endpoint: ${detail.take(80)}"
      case other => other.toString.take(120)

  /** How a request for a code ended up, before it is read as sent, refused or other: the page it landed on (`None`: an
    * error redirect, which is a refusal), what it paid with, or that a challenge was left unanswered. */
  private enum Landed:
    case Page(page: Option[ChallengePage], paid: Paid, micros: Long)
    case Unanswered(reason: String)

  /** What a request that was challenged paid with to be sent its code. */
  private enum Paid:
    case No, Work, Waiting

  /** A code the conversation ended with, and the verifier it is redeemed with. */
  case class Granted(code: AuthCode, verifier: CodeVerifier)

  enum Requested:
    /** The conversation went on to the code step. */
    case Sent(started: AuthorizeStarted, page: ChallengePage, requestMicros: Long = 0L)

    /** The conversation ended in "access denied". */
    case Refused

    /** Any other page. */
    case Other(step: String)

  private def required[A](value: Option[A], endpoint: String, detail: String): IO[ProtocolError, A] =
    ZIO.fromOption(value).orElseFail(ProtocolError.MalformedResponse(endpoint, detail))

  /** A query parameter of a redirect's `Location`, which may be relative. */
  private[antifraud] def param(location: String, name: String): Option[String] =
    URL.decode(location).toOption.flatMap(_.queryParams.getAll(name).headOption)
