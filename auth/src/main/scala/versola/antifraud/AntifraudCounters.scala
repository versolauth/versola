package versola.antifraud

import versola.util.Phone
import zio.*
import zio.json.JsonCodec
import zio.metrics.{Metric, MetricLabel}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/** What a reset covers: the velocity counters, the SMS budget, or both. */
enum CounterScope derives JsonCodec:
  case velocity, budget, all

  def velocityToo: Boolean = this != budget
  def budgetToo: Boolean = this != velocity

/** What of the scope a reset is about; without one, all of it. */
enum CounterValueType derives JsonCodec:
  case phone, ip, prefix

/** A reset as the instances read it back. */
case class CounterReset(
    generation: Long,
    scope: CounterScope,
    valueType: Option[CounterValueType],
    valueHash: Option[String],
    destPrefix: Option[String],
    requestedBy: String,
    at: Instant,
)

object CounterReset:
  /** A request made into what is stored: `valueHash` is the key the velocity counters hold the value under (the keyed
    * hash of a phone, of an address's network, the digits of a prefix) and `destPrefix` the digits the SMS budget is
    * keyed by (the leading digits of a phone number, or the prefix); neither is the value itself beyond digits a log
    * already keeps. */
  case class Resolved(scope: CounterScope, valueType: Option[CounterValueType], valueHash: Option[String], destPrefix: Option[String])

/** The request is not valid: a value without its type, a value that is not of its type, an address for the budget. */
final class InvalidCounterReset(message: String) extends RuntimeException(message)

/** Forgets what the counters have counted: a phone number whose owner is told no code is coming (a support case), a
  * range of numbers that was closed in error, or everything, as a load test needs between runs (what `TRUNCATE
  * af_velocity` did, which left the memory of every instance as it was).
  *
  * A reset is a row ([[AntifraudCounterResetRepository]]) with a generation, and the instances read what is new every
  * `antifraud.tuning.refresh-interval`, as they read the tuning: each forgets its memory of the target (its own counts
  * and what it has loaded of the others'), deletes its own rows of `af_velocity` for it, and forgets what it loaded of the
  * others once more a little later, so that a count another instance wrote between its last flush and its own reset is not
  * kept. So a reset is complete on every instance within two refresh intervals; the instance that took the request forgets at
  * once. A request that is counted while the reset is applied is counted as before it, or not at all: nothing
  * is lost beyond the few seconds it takes.
  *
  * The SMS budget is keyed by tenant and range of numbers: a reset of a phone number or a prefix forgets the ranges that
  * contain it (or are contained by it) of every tenant, and a reset of an address leaves the budget alone, since it has
  * no address.
  */
trait AntifraudCounters:
  /** Makes the request, forgets what it says on this instance and returns the generation the others will apply.
    * Fails with [[InvalidCounterReset]]. */
  def reset(scope: CounterScope, valueType: Option[CounterValueType], value: Option[String], actor: String): Task[Long]

object AntifraudCounters:

  /** Resets read per poll, at most; more are read by the next one. */
  private val PollBatch = 100

  val live: ZLayer[
    AntifraudCounterResetRepository & VelocityRepository & VelocityCounters & AntifraudEvents & AntifraudTuningStore.Settings & Scope,
    Nothing,
    AntifraudCounters,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudCounterResetRepository]
        velocityRepository <- ZIO.service[VelocityRepository]
        counters <- ZIO.service[VelocityCounters]
        events <- ZIO.service[AntifraudEvents]
        interval <- ZIO.serviceWith[AntifraudTuningStore.Settings](_.refreshInterval)
        impl = Impl(repository, velocityRepository, counters, events, interval)
        _ <- impl.start
      yield impl

  class Impl(
      repository: AntifraudCounterResetRepository,
      velocityRepository: VelocityRepository,
      counters: VelocityCounters,
      events: AntifraudEvents,
      pollInterval: Duration,
  ) extends AntifraudCounters:

    // The last generation this instance has applied, or -1 before it knows where the log stands.
    private val applied = AtomicLong(-1L)
    // Generations this instance applied when it took the request, which the poll must not apply again.
    private val appliedHere = ConcurrentHashMap.newKeySet[java.lang.Long]()
    // Resets whose second pass (forget what was loaded of the others, delete own rows again) is due, and when.
    private val settling = AtomicReference(List.empty[(Instant, CounterReset)])

    // How long after it a reset is gone from what the others hold: they apply it within a poll, and a load is a
    // few seconds more; twice the poll covers the first and the usual second.
    private val settleAfter = pollInterval.multipliedBy(2)

    override def reset(scope: CounterScope, valueType: Option[CounterValueType], value: Option[String], actor: String): Task[Long] =
      for
        resolved <- resolve(scope, valueType, value)
        now <- Clock.instant
        generation <- repository.record(resolved, actor, now)
        reset = CounterReset(generation, scope, resolved.valueType, resolved.valueHash, resolved.destPrefix, actor, now)
        _ <- ZIO.succeed(appliedHere.add(generation))
        // This instance forgets at once, and deletes every instance's rows: those of an instance that is gone are nobody's
        // to delete otherwise. The others delete their own as they apply it.
        _ <- apply(reset, everyRow = true)
        _ <- schedule(reset, now)
        _ <- AntifraudCountersMetrics.reset(scope)
      yield generation

    private def resolve(scope: CounterScope, valueType: Option[CounterValueType], value: Option[String]): Task[CounterReset.Resolved] =
      def invalid(message: String) = ZIO.fail(InvalidCounterReset(message))
      (valueType, value.map(_.trim)) match
        case (None, None) => ZIO.succeed(CounterReset.Resolved(scope, None, None, None))
        case (Some(_), None) | (None, Some(_)) => invalid("give valueType and value together, or neither")
        case (Some(_), Some(v)) if v.isEmpty || v.length > 64 => invalid("value is empty or too long")
        case (Some(CounterValueType.ip), _) if !scope.velocityToo => invalid("an address has no SMS budget: scope is velocity or all")
        case (Some(CounterValueType.phone), Some(v)) =>
          ZIO.fromEither(Phone.parse(v)).mapError(_ => InvalidCounterReset("value is not a valid phone number")).flatMap: phone =>
            events.subjectHash(Right(phone)).map: hash =>
              CounterReset.Resolved(
                scope,
                Some(CounterValueType.phone),
                Some(hash),
                Some(phone.filter(_.isDigit).take(AntifraudEvents.destPrefixLength)),
              )
        case (Some(CounterValueType.ip), Some(v)) =>
          counters.ipTarget(v).flatMap:
            case Some(VelocityTarget.Key(_, key)) => ZIO.succeed(CounterReset.Resolved(scope, Some(CounterValueType.ip), Some(key), None))
            case _ => invalid("value is not an IP address")
        case (Some(CounterValueType.prefix), Some(v)) =>
          if v.forall(_.isDigit) && v.length <= AntifraudEvents.destPrefixLength then
            ZIO.succeed(CounterReset.Resolved(scope, Some(CounterValueType.prefix), Some(v), Some(v)))
          else invalid(s"a prefix is 1 to ${AntifraudEvents.destPrefixLength} digits, without the +")

    private def velocityTarget(reset: CounterReset): VelocityTarget =
      (reset.valueType, reset.valueHash) match
        case (Some(CounterValueType.phone), Some(hash)) => VelocityTarget.Key(VelocityKey.phone, hash)
        case (Some(CounterValueType.ip), Some(key)) => VelocityTarget.Key(VelocityKey.ip, key)
        case (Some(CounterValueType.prefix), Some(digits)) => VelocityTarget.Range(digits)
        case _ => VelocityTarget.All

    /** Forgets the target in memory, then in the database: `everyRow` for the instance that took the request. A
      * failure of the database is counted and logged, and does not stop the memory from being cleared. */
    private def apply(reset: CounterReset, everyRow: Boolean): UIO[Unit] =
      val target = velocityTarget(reset)
      val budgetPrefix = reset.valueType match
        case None => Some(None)
        case Some(CounterValueType.ip) => None
        case Some(_) => Some(reset.destPrefix)
      ZIO.when(reset.scope.velocityToo)(ZIO.succeed(counters.forget(target)) *> deleteRows(target, everyRow)) *>
        ZIO.foreachDiscard(budgetPrefix.filter(_ => reset.scope.budgetToo))(events.resetBudget)

    private def deleteRows(target: VelocityTarget, everyRow: Boolean): UIO[Unit] =
      velocityRepository.forget(target, Option.unless(everyRow)(counters.instanceId)).unit.catchAllCause: cause =>
        AntifraudCountersMetrics.failed("delete") *> ZIO.logWarningCause("could not delete the antifraud velocity rows of a reset", cause)

    private def schedule(reset: CounterReset, now: Instant): UIO[Unit] =
      ZIO.succeed(settling.updateAndGet((now.plus(settleAfter), reset) :: _)).unit

    private def takeDue(now: Instant): List[(Instant, CounterReset)] =
      // Taken whole and the later ones put back, so that a reset scheduled meanwhile is kept.
      val (due, later) = settling.getAndSet(Nil).partition(!_._1.isAfter(now))
      settling.updateAndGet(later ++ _)
      due

    /** The second pass of resets that were applied long enough ago. */
    private val settle: UIO[Unit] =
      Clock.instant.flatMap: now =>
        ZIO.succeed(takeDue(now)).flatMap: due =>
          ZIO.foreachDiscard(due): (_, reset) =>
            ZIO.when(reset.scope.velocityToo):
              val target = velocityTarget(reset)
              ZIO.succeed(counters.forgetRemote(target)) *> deleteRows(target, everyRow = false)

    /** Applies what is new: the resets after the last generation this instance has seen. The first poll only finds out
      * where the log stands: what was reset before this instance started is not in its memory. */
    private val poll: Task[Unit] =
      repository.latest.flatMap: latest =>
        val seen = applied.get
        if seen < 0 then ZIO.succeed(applied.set(latest))
        else if latest <= seen then ZIO.unit
        else
          repository.since(seen, PollBatch).flatMap: resets =>
            ZIO.foreachDiscard(resets): reset =>
              for
                took <- ZIO.succeed(appliedHere.remove(reset.generation))
                now <- Clock.instant
                _ <- ZIO.unless(took)(apply(reset, everyRow = false) *> schedule(reset, now))
                _ <- ZIO.succeed(applied.set(reset.generation))
              yield ()

    private[antifraud] val pollOnce: UIO[Unit] =
      settle *> poll.foldCauseZIO(
        cause => AntifraudCountersMetrics.failed("poll") *> ZIO.logWarningCause("could not read the antifraud counter resets", cause),
        _ => ZIO.unit,
      )

    /** The first poll is made before the layer is built (so that a reset made while this instance starts is not missed),
      * the rest on a fiber of their own, which stops with the scope. */
    val start: URIO[Scope, Unit] =
      pollOnce *> (ZIO.sleep(pollInterval) *> pollOnce).forever.forkScoped.unit

object AntifraudCountersMetrics:
  private val resetTotal = Metric.counter("antifraud_counters_reset_total")
  private val failedTotal = Metric.counter("antifraud_counters_reset_failed_total")

  /** By scope, a closed set. */
  def reset(scope: CounterScope): UIO[Unit] = resetTotal.tagged(Set(MetricLabel("scope", scope.toString))).increment

  /** `what` is `poll` or `delete`: a closed set. A poll that fails leaves the instance with the counts it has until the next. */
  def failed(what: String): UIO[Unit] = failedTotal.tagged(Set(MetricLabel("what", what))).increment
