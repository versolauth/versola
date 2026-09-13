package versola.loadgen.protocol

import zio.{UIO, ZIO}

/** The flows of §8, named as the campaign reports them. `mobile-*` match the client ids of
  * design doc §2.2 because one client is exactly one flow.
  */
enum FlowName(val value: String):
  case MobileOtp extends FlowName("mobile-otp")
  case MobileOtpPassword extends FlowName("mobile-otp-password")
  case MobilePasskey extends FlowName("mobile-passkey")
  case Refresh extends FlowName("refresh")
  case BusinessAction extends FlowName("business-action")
  case Logout extends FlowName("logout")

/** One measured hop. Every `/challenge` fetch is the same step name regardless of which step of
  * the conversation it rendered -- the hop is the HTTP round trip, and what the SUT spent it on
  * is the next submit's business.
  */
enum StepName(val value: String):
  case Authorize extends StepName("authorize")
  case Challenge extends StepName("challenge")
  case SubmitPhone extends StepName("submit-phone")
  case SubmitOtp extends StepName("submit-otp")
  case SubmitPassword extends StepName("submit-password")
  case PasskeyOptions extends StepName("passkey-options")
  case SubmitPasskey extends StepName("submit-passkey")
  case TokenCode extends StepName("token-code")
  case TokenRefresh extends StepName("token-refresh")
  case Action extends StepName("action")
  case Logout extends StepName("logout")

/** Where the timings of §8 ("each hop a separately timed step; each flow also timed end to end")
  * go.
  *
  * A callback rather than a return value, for two reasons: a flow that fails part-way still
  * reports every hop it did complete plus the one that failed -- which is the interesting case,
  * and the one a `FlowResult` return type would drop -- and track F (#275) owns what the numbers
  * become (HdrHistogram, the error taxonomy) without this having to anticipate it.
  *
  * `error` is `None` on success, so the hot path allocates nothing here.
  */
trait FlowObserver:
  def step(flow: FlowName, step: StepName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit]

  def flow(flow: FlowName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit]

object FlowObserver:
  /** For calibration runs and tests, where the protocol client is exercised but nothing is being
    * measured.
    */
  val none: FlowObserver = new FlowObserver:
    override def step(flow: FlowName, step: StepName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] = ZIO.unit
    override def flow(flow: FlowName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] = ZIO.unit
