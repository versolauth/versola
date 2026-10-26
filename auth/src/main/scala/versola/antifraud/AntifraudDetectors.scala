package versola.antifraud

import zio.*
import zio.metrics.{Metric, MetricLabel}

/** The detectors: every minute they read the per-range rollup (`af_dest_stats`) and propose list entries
  * (`af_proposal`) for what looks like toll fraud. They only propose: nothing here changes what a rule does, and an
  * entry is made only when a proposal is approved (see [[AntifraudProposal.toNewListEntry]]).
  *
  * Off by default (`antifraud.detectors.enabled`). The work of a run is bounded (a window of the rollup, a cap on
  * the proposals made), runs under a statement timeout, and is done by one instance at a time: an instance that
  * finds another one in the middle of a detector goes on to the next.
  *
  * Not done: a detector of sequential numbers. The log keeps a number as a keyed hash and its first six digits, so
  * numbers that follow one another cannot be told from numbers that do not without keeping more of the number
  * than the log is allowed to.
  */
trait AntifraudDetectors:
  /** One pass of every enabled detector. A failure of one is counted and logged and does not stop the others. */
  def runOnce: UIO[Unit]

object AntifraudDetectors:

  /** @param enabled          off by default: no detector runs
    * @param interval         how often the detectors run
    * @param settle           how much of the newest rollup is left out: the rollup of a minute is rewritten for
    *                         `stats-lookback` after it, and a code is entered some time after it is sent
    * @param statementTimeout the longest a statement of a run may take
    * @param maxProposals     the most proposals one detector makes in a run
    * @param candidates       the most ranges one run of a detector looks at closely, busiest first
    * @param openFor          how long a proposal waits for a decision before it expires
    * @param cooldown         how long after it was approved or rejected a value is not proposed again
    * @param ttl              how long an entry lives that is made from a proposal
    * @param retention        how long a decided or expired proposal is kept
    * @param batchSize        the most proposals expired, and the most removed, in one statement
    */
  case class Settings(
      enabled: Boolean,
      interval: Duration,
      settle: Duration,
      statementTimeout: Duration,
      maxProposals: Int,
      candidates: Int,
      openFor: Duration,
      cooldown: Duration,
      ttl: Duration,
      retention: Duration,
      batchSize: Int,
      prefixAnomaly: PrefixAnomaly,
      fakeVerify: FakeVerify,
  )

  /** A range is proposed (as `fake`) when, in the last `window`, it was asked for at least `minRequests` codes,
    * that is `spikeFactor` times what the `baseline` before it gives for a window of that length (at least
    * `baselineFloor`, so a range that was quiet is not a spike at the first few requests), and its conversion
    * (verified over sent) is at most `maxConversion` and, where the baseline sent at least `minBaselineSent`, at most
    * `conversionDrop` times the conversion of the baseline. At least `minSent` codes must have been sent: a spike
    * that cost nothing is not toll fraud.
    */
  case class PrefixAnomaly(
      enabled: Boolean,
      window: Duration,
      baseline: Duration,
      minRequests: Int,
      spikeFactor: Double,
      baselineFloor: Double,
      minSent: Int,
      maxConversion: Double,
      conversionDrop: Double,
      minBaselineSent: Int,
  )

  /** A range is proposed (as `deny`) when, in the last `window`, at least `minFakeVerifies` codes were entered
    * after a fake send (nobody got a code, so whoever entered one is guessing or a script) and at most
    * `maxVerified` real ones were verified. */
  case class FakeVerify(
      enabled: Boolean,
      window: Duration,
      minFakeVerifies: Int,
      maxVerified: Int,
  )

  object Settings:
    val default: Settings = Settings(
      enabled = false,
      interval = 1.minute,
      settle = 2.minutes,
      statementTimeout = 10.seconds,
      maxProposals = 20,
      candidates = 200,
      openFor = 12.hours,
      cooldown = 24.hours,
      ttl = 24.hours,
      retention = 90.days,
      batchSize = 1000,
      prefixAnomaly = PrefixAnomaly(
        enabled = true,
        window = 15.minutes,
        baseline = 24.hours,
        minRequests = 50,
        spikeFactor = 5.0,
        baselineFloor = 5.0,
        minSent = 20,
        maxConversion = 0.2,
        conversionDrop = 0.5,
        minBaselineSent = 50,
      ),
      fakeVerify = FakeVerify(
        enabled = true,
        window = 30.minutes,
        minFakeVerifies = 5,
        maxVerified = 0,
      ),
    )

  /** What every detector statement needs of the settings, so the repository does not take the whole of them. */
  case class Run(settle: Duration, statementTimeout: Duration, maxProposals: Int, candidates: Int, cooldown: Duration, ttl: Duration)

  val live: ZLayer[AntifraudProposalRepository & Settings & Scope, Nothing, AntifraudDetectors] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudProposalRepository]
        settings <- ZIO.service[Settings]
        detectors = Impl(repository, settings)
        _ <- ZIO.when(settings.enabled)((ZIO.sleep(settings.interval) *> detectors.runOnce).forever.forkScoped)
      yield detectors

  class Impl(repository: AntifraudProposalRepository, settings: Settings) extends AntifraudDetectors:
    private val run = Run(
      settle = settings.settle,
      statementTimeout = settings.statementTimeout,
      maxProposals = settings.maxProposals,
      candidates = settings.candidates,
      cooldown = settings.cooldown,
      ttl = settings.ttl,
    )

    private def detect(kind: ProposalKind, made: Task[Option[Int]]): UIO[Unit] =
      made.foldCauseZIO(
        cause => AntifraudDetectorMetrics.failed(kind) *> ZIO.logWarningCause(s"antifraud detector ${kind.toString} failed", cause),
        {
          case Some(count) => AntifraudDetectorMetrics.proposed(kind, count)
          case None => ZIO.unit
        },
      )

    override def runOnce: UIO[Unit] =
      Clock.instant.flatMap: now =>
        ZIO.when(settings.prefixAnomaly.enabled)(
          detect(ProposalKind.prefix_anomaly, repository.detectPrefixAnomalies(now, settings.prefixAnomaly, run)),
        ) *>
          ZIO.when(settings.fakeVerify.enabled)(
            detect(ProposalKind.fake_verify, repository.detectFakeVerifies(now, settings.fakeVerify, run)),
          ) *>
          housekeeping(now)

    // Without this an open proposal that nobody decides would block its value for ever, and the table would only grow.
    private def housekeeping(now: java.time.Instant): UIO[Unit] =
      (repository.expireOpen(now.minusSeconds(settings.openFor.getSeconds), settings.batchSize, settings.statementTimeout) *>
        repository.prune(now.minusSeconds(settings.retention.getSeconds), settings.batchSize, settings.statementTimeout)).foldCauseZIO(
        cause => AntifraudDetectorMetrics.housekeepingFailed *> ZIO.logWarningCause("antifraud proposals could not be expired or removed", cause),
        _ => ZIO.unit,
      )

object AntifraudDetectorMetrics:
  private val proposedTotal = Metric.counter("antifraud_proposals_created_total")
  private val failedTotal = Metric.counter("antifraud_detector_failed_total")
  private val housekeepingFailedTotal = Metric.counter("antifraud_proposal_housekeeping_failed_total")

  def proposed(kind: ProposalKind, count: Int): UIO[Unit] =
    proposedTotal.tagged(Set(MetricLabel("kind", kind.toString))).incrementBy(count.toLong)

  def failed(kind: ProposalKind): UIO[Unit] = failedTotal.tagged(Set(MetricLabel("kind", kind.toString))).increment

  val housekeepingFailed: UIO[Unit] = housekeepingFailedTotal.increment
