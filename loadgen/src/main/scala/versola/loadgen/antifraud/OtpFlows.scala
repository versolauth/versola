package versola.loadgen.antifraud

import versola.loadgen.protocol.*
import zio.http.URL
import zio.{Clock, Duration, IO, ZIO}

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
    measureOtp: Boolean = true,
):
  import OtpFlows.*

  private val creds = ClientCreds(config.clientId, None)

  /** Starts a conversation and gives a number to it. */
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
        submitted <- timed("otp-request", measureOtp)(auth.submitPhone(conversation = conversation, phone = phone, csrf = csrf))
        // A refusal is an error redirect, or the access-denied page the next fetch of the conversation renders.
        next <- submitted match
          case SubmitOutcome.Rendered(next) => ZIO.some(next)
          case SubmitOutcome.Redirected(location, _) =>
            if param(location, "error").isDefined then ZIO.none else auth.challenge(conversation).asSome
      yield next.fold(Requested.Refused: Requested): page =>
        page.step match
          case Some(ConversationStep.Otp) => Requested.Sent(started, page)
          case Some(ConversationStep.AccessDenied) => Requested.Refused
          case other => Requested.Other(other.fold("none")(_.value))
    ZIO.succeed(tally.requested.increment()) *> request.tapBoth(
      error => ZIO.succeed(tally.fail(OtpFlows.reason(error))),
      {
        case _: Requested.Sent => ZIO.succeed(tally.sent.increment())
        case Requested.Refused => ZIO.succeed(tally.refused.increment())
        case _: Requested.Other => ZIO.succeed(tally.other.increment())
      },
    )

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

  /** A code the conversation ended with, and the verifier it is redeemed with. */
  case class Granted(code: AuthCode, verifier: CodeVerifier)

  enum Requested:
    /** The conversation went on to the code step. */
    case Sent(started: AuthorizeStarted, page: ChallengePage)

    /** The conversation ended in "access denied". */
    case Refused

    /** Any other page. */
    case Other(step: String)

  private def required[A](value: Option[A], endpoint: String, detail: String): IO[ProtocolError, A] =
    ZIO.fromOption(value).orElseFail(ProtocolError.MalformedResponse(endpoint, detail))

  /** A query parameter of a redirect's `Location`, which may be relative. */
  private[antifraud] def param(location: String, name: String): Option[String] =
    URL.decode(location).toOption.flatMap(_.queryParams.getAll(name).headOption)
