package versola.util.http

import zio.*
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** What an operator sets, per edge, for how the edge behaves when it is asked for more than it can do: the wire form held by
  * central and sent to the edge.
  *
  * Every field is optional and means "what the process itself starts with" when absent (the built-in defaults, or the
  * `HTTP_ADMISSION_*` / `HTTP_CLIENT_GUARD_*` environment), so a policy is a patch over the deployment and an operator
  * who sets one field has not pinned the rest. The edge keeps the last policy it received and applies it while central is
  * away.
  *
  * Applied to [[InboundAdmissionSettings]] and [[OutboundGuardSettings]] with [[applyInbound]] and [[applyOutbound]];
  * checked with [[validate]], which central runs before storing and the edge before applying: a policy that fails is not
  * applied, and the edge keeps what it had.
  */
final case class OverloadPolicy(
    inbound: Option[InboundPolicy] = None,
    outbound: Option[OutboundPolicy] = None,
) derives JsonCodec, Schema:

  /** The first thing wrong with the policy, if anything. */
  def validate: Either[String, OverloadPolicy] =
    for
      _ <- inbound.fold(Right(()))(_.validate)
      _ <- outbound.fold(Right(()))(_.validate)
    yield this

  def applyInbound(base: InboundAdmissionSettings): InboundAdmissionSettings =
    inbound.fold(base)(_.applyTo(base))

  def applyOutbound(base: OutboundGuardSettings): OutboundGuardSettings =
    outbound.fold(base)(_.applyTo(base))

object OverloadPolicy:
  val empty: OverloadPolicy = OverloadPolicy()

  private[http] def check(ok: Boolean, message: => String): Either[String, Unit] = if ok then Right(()) else Left(message)

  private[http] def inRange(name: String, value: Option[Int], min: Int, max: Int): Either[String, Unit] =
    check(value.forall(v => v >= min && v <= max), s"$name must be between $min and $max")

  private[http] def inRange(name: String, value: Option[Double], min: Double, max: Double): Either[String, Unit] =
    check(value.forall(v => v >= min && v <= max), s"$name must be between $min and $max")

  private[http] def modeOf(raw: Option[String]): Either[String, Unit] =
    check(raw.forall(m => GuardMode.parse(m).isDefined), "mode must be off, shadow or enforce")

/** @param mode `off`, `shadow` or `enforce` */
final case class InboundPolicy(
    mode: Option[String] = None,
    maxInFlight: Option[Int] = None,
    continuationShare: Option[Double] = None,
    startShare: Option[Double] = None,
    retryAfterSeconds: Option[Int] = None,
    adaptive: Option[AdaptivePolicy] = None,
) derives JsonCodec, Schema:
  import OverloadPolicy.*

  def validate: Either[String, Unit] =
    for
      _ <- modeOf(mode)
      _ <- inRange("inbound.maxInFlight", maxInFlight, 1, 100000)
      _ <- inRange("inbound.continuationShare", continuationShare, 0.01, 1.0)
      _ <- inRange("inbound.startShare", startShare, 0.01, 1.0)
      _ <- check(
        (startShare, continuationShare) match
          case (Some(start), Some(cont)) => start <= cont
          case _ => true,
        "inbound.startShare must not exceed inbound.continuationShare: a new login never gets more room than work already begun",
      )
      _ <- inRange("inbound.retryAfterSeconds", retryAfterSeconds, 1, 60)
      _ <- adaptive.fold(Right(()))(_.validate)
    yield ()

  def applyTo(base: InboundAdmissionSettings): InboundAdmissionSettings =
    val cont = continuationShare.getOrElse(base.continuationShare)
    base.copy(
      mode = mode.flatMap(GuardMode.parse).getOrElse(base.mode),
      maxInFlight = maxInFlight.getOrElse(base.maxInFlight),
      continuationShare = cont,
      // a lower tier never gets more room than a higher one, whatever the two sources said
      startShare = math.min(startShare.getOrElse(base.startShare), cont),
      retryAfterSeconds = retryAfterSeconds.getOrElse(base.retryAfterSeconds),
      adaptive = adaptive.fold(base.adaptive)(_.applyTo(base.adaptive)),
    )

/** Bounds and gains of the learned limit. The limit is always learned while the inbound mode is not `off`; there is no switch.
  * A leftover `enabled` field from an earlier draft of the wire form is ignored (unknown fields are).
  */
final case class AdaptivePolicy(
    minLimit: Option[Int] = None,
    maxLimit: Option[Int] = None,
    tolerance: Option[Double] = None,
    smoothing: Option[Double] = None,
    lagTargetMillis: Option[Int] = None,
) derives JsonCodec, Schema:
  import OverloadPolicy.*

  def validate: Either[String, Unit] =
    for
      _ <- inRange("inbound.adaptive.minLimit", minLimit, 1, 100000)
      _ <- inRange("inbound.adaptive.maxLimit", maxLimit, 1, 100000)
      _ <- check(
        (minLimit, maxLimit) match
          case (Some(min), Some(max)) => min <= max
          case _ => true,
        "inbound.adaptive.minLimit must not exceed inbound.adaptive.maxLimit",
      )
      _ <- inRange("inbound.adaptive.tolerance", tolerance, 1.0, 5.0)
      _ <- inRange("inbound.adaptive.smoothing", smoothing, 0.01, 1.0)
      _ <- inRange("inbound.adaptive.lagTargetMillis", lagTargetMillis, 5, 10000)
    yield ()

  def applyTo(base: AdaptiveSettings): AdaptiveSettings =
    val min = minLimit.getOrElse(base.minLimit)
    AdaptiveSettings(
      minLimit = min,
      maxLimit = math.max(min, maxLimit.getOrElse(base.maxLimit)),
      tolerance = tolerance.getOrElse(base.tolerance),
      smoothing = smoothing.getOrElse(base.smoothing),
      lagTarget = lagTargetMillis.fold(base.lagTarget)(_.millis),
    )

/** @param mode `off`, `shadow` or `enforce` */
final case class OutboundPolicy(
    mode: Option[String] = None,
    maxConcurrent: Option[Int] = None,
    maxQueue: Option[Int] = None,
    queueTimeoutMillis: Option[Int] = None,
    requestTimeoutMillis: Option[Int] = None,
    breaker: Option[BreakerPolicy] = None,
    retryBudget: Option[RetryBudgetPolicy] = None,
) derives JsonCodec, Schema:
  import OverloadPolicy.*

  def validate: Either[String, Unit] =
    for
      _ <- modeOf(mode)
      _ <- inRange("outbound.maxConcurrent", maxConcurrent, 1, 100000)
      _ <- inRange("outbound.maxQueue", maxQueue, 1, 100000)
      _ <- inRange("outbound.queueTimeoutMillis", queueTimeoutMillis, 1, 600000)
      _ <- inRange("outbound.requestTimeoutMillis", requestTimeoutMillis, 1, 600000)
      _ <- breaker.fold(Right(()))(_.validate)
      _ <- retryBudget.fold(Right(()))(_.validate)
    yield ()

  def applyTo(base: OutboundGuardSettings): OutboundGuardSettings =
    base.copy(
      mode = mode.flatMap(GuardMode.parse).getOrElse(base.mode),
      maxConcurrent = maxConcurrent.getOrElse(base.maxConcurrent),
      maxQueue = maxQueue.getOrElse(base.maxQueue),
      queueTimeout = queueTimeoutMillis.fold(base.queueTimeout)(_.millis),
      requestTimeout = requestTimeoutMillis.fold(base.requestTimeout)(_.millis),
      breaker = breaker.fold(base.breaker)(_.applyTo(base.breaker)),
      retryBudget = retryBudget.fold(base.retryBudget)(_.applyTo(base.retryBudget)),
    )

final case class BreakerPolicy(
    enabled: Option[Boolean] = None,
    failureRatio: Option[Double] = None,
    minCalls: Option[Int] = None,
    windowSeconds: Option[Int] = None,
    openSeconds: Option[Int] = None,
    probeCalls: Option[Int] = None,
) derives JsonCodec, Schema:
  import OverloadPolicy.*

  def validate: Either[String, Unit] =
    for
      _ <- inRange("outbound.breaker.failureRatio", failureRatio, 0.01, 1.0)
      _ <- inRange("outbound.breaker.minCalls", minCalls, 1, 10000)
      _ <- inRange("outbound.breaker.windowSeconds", windowSeconds, 1, 300)
      _ <- inRange("outbound.breaker.openSeconds", openSeconds, 1, 600)
      _ <- inRange("outbound.breaker.probeCalls", probeCalls, 1, 100)
    yield ()

  def applyTo(base: BreakerSettings): BreakerSettings =
    BreakerSettings(
      enabled = enabled.getOrElse(base.enabled),
      failureRatio = failureRatio.getOrElse(base.failureRatio),
      minCalls = minCalls.getOrElse(base.minCalls),
      window = windowSeconds.fold(base.window)(_.seconds),
      openFor = openSeconds.fold(base.openFor)(_.seconds),
      probeCalls = probeCalls.getOrElse(base.probeCalls),
    )

final case class RetryBudgetPolicy(
    ratio: Option[Double] = None,
    minPerSecond: Option[Double] = None,
) derives JsonCodec, Schema:
  import OverloadPolicy.*

  def validate: Either[String, Unit] =
    for
      _ <- inRange("outbound.retryBudget.ratio", ratio, 0.0, 1.0)
      _ <- inRange("outbound.retryBudget.minPerSecond", minPerSecond, 0.0, 1000.0)
    yield ()

  def applyTo(base: RetryBudgetSettings): RetryBudgetSettings =
    RetryBudgetSettings(ratio = ratio.getOrElse(base.ratio), minPerSecond = minPerSecond.getOrElse(base.minPerSecond))
