package versola.util.http

import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicLongArray, AtomicReference, LongAdder}

/** The answer to one check. A refusal names the policy and says when to come back; it never carries the key. */
enum RateDecision:
  case Allowed
  case Limited(policy: String, retryAfterSeconds: Int)

/** What the request is, for the dimensions a policy can be keyed on. An empty string means "not known at this point"
  * (no `sid` in the token, no address yet): a policy that needs it does not apply to the request, rather than sharing one
  * bucket among everything that lacks it.
  *
  * Built once per request, and only when a policy applies, so a deployment with no policies pays nothing for it.
  */
final class RateLimitKeys(
    val ip: String = "",
    val certificate: String = "",
    val client: String = "",
    val subject: String = "",
    val session: String = "",
    val jkt: String = "",
    val resource: String = "",
    val endpoint: String = "",
)

/** What the limiter is allowed to hold.
  *
  * @param maxKeysPerPolicy
  *   tracked keys per policy; past it new keys share one overflow bucket, so a flood of distinct keys cannot grow memory
  */
final case class RateLimiterSettings(maxKeysPerPolicy: Int)

object RateLimiterSettings:
  val default: RateLimiterSettings = RateLimiterSettings(maxKeysPerPolicy = 100_000)

/** Rate limits on traffic, per key, in memory, with no lock and no allocation on the usual path.
  *
  * The algorithm is GCRA (the generic cell rate algorithm), a token bucket whose whole state is one number: the
  * theoretical arrival time `tat`, the instant at which the bucket would be empty again. One request takes `cost * T`
  * nanoseconds of it, `T = windowSeconds / limit`, and is admitted while `tat - burst * T` has not run ahead of the
  * clock; so a check is one compare-and-set on one `Long`, and there is nothing to refill. A policy with several windows
  * keeps one `tat` per window, and a request passes only if every window does.
  *
  * **All or nothing.** A request is checked against every policy that applies to it before any of them is charged
  * (the lesson of `SubmissionLimiter.tryAcquire`: a flood already refused at one level must not keep draining the buckets
  * below it). The charge itself is a compare-and-set that repeats the check, so two requests racing for the last token
  * can never both take it; if the race is lost on a later window the earlier charges are returned.
  *
  * **Memory.** A key whose bucket has refilled holds no information, so it is dropped: [[sweep]] removes such keys, and
  * runs by itself when a policy is at its key cap. The cap is soft by the number of threads inserting at once.
  *
  * **Modes.** `shadow` counts what `enforce` would refuse and refuses nothing; a request a shadow policy would refuse
  * does not charge that policy, so its state is what `enforce` would have kept.
  *
  * Time is passed in (`nowNanos`, from `Clock.nanoTime` in the service), so the arithmetic is tested without a clock.
  *
  * Metrics, published by [[publish]]: `edge_rate_limit_decisions_total{policy,result}` (`allowed`, `limited`, `would_limit`),
  * `edge_rate_limit_tracked_keys{policy}` and `edge_rate_limit_overflow_hits_total{policy}`. A key is never a label.
  */
final class RateLimiter private (settings: RateLimiterSettings, installed: AtomicReference[RateLimiter.Installed], metricPrefix: String):
  import RateLimiter.*

  private val decisions = Metric.counter(s"${metricPrefix}_rate_limit_decisions_total")
  private val overflowHitsMetric = Metric.counter(s"${metricPrefix}_rate_limit_overflow_hits_total")
  private val trackedKeys = Metric.gauge(s"${metricPrefix}_rate_limit_tracked_keys")

  /** Whether any policy applies at `point`: one volatile read, which is all a deployment with no policies pays. */
  def active(point: Checkpoint): Boolean = installed.get.at(point.ordinal).length > 0

  /** Replaces the policies. A policy whose limits, burst, cost, scope and dimensions did not change keeps its buckets, so
    * switching a mode or an unrelated policy does not hand every key a full bucket. The policies are expected to have passed
    * [[RateLimitPolicy.validateAll]].
    */
  def update(policies: List[RateLimitPolicy], replicas: Int = 1): UIO[Unit] =
    publish *> ZIO.succeed:
      val before = installed.get
      val runtimes = policies.filter(_.mode != GuardMode.Off).map: policy =>
        val shape = Shape.of(policy, settings, math.max(1, replicas))
        val reused = before.byName.get(policy.name).map(_.tracker).filter(_.shape == shape)
        new Policy(policy, reused.getOrElse(new Tracker(shape)))
      installed.set(Installed.of(runtimes))
      before.byName.keySet -- runtimes.map(_.policy.name)
    .flatMap(gone => ZIO.foreachDiscard(gone)(forget))

  /** The `RateLimit-Policy` and `RateLimit` fields of a refusal (draft-ietf-httpapi-ratelimit-headers, a draft that is still
    * moving: sent on `429` only, so a change of the draft cannot break a successful response). `q` and `w` are the quota and window
    * of the policy's tightest rate; `r` is 0 and `t` is the wait in seconds. Never the key, never other policies.
    */
  def responseHeaders(limited: RateDecision.Limited): List[(String, String)] =
    installed.get.byName.get(limited.policy).toList.flatMap: p =>
      val tightest = p.policy.limits.minBy(window => window.limit.toDouble / window.windowSeconds)
      List(
        "RateLimit-Policy" -> s"\"${limited.policy}\";q=${tightest.limit};w=${tightest.windowSeconds}",
        "RateLimit" -> s"\"${limited.policy}\";r=0;t=${limited.retryAfterSeconds}",
      )

  /** The names of the policies being enforced or shadowed now. */
  def names: List[String] = installed.get.all.map(_.policy.name).toList

  /** Checks a request at `point` against every policy that applies to it. Allocation-free for one policy and a key that
    * is one of the request's own values; a refusal allocates its answer.
    */
  def check(point: Checkpoint, keys: RateLimitKeys, nowNanos: Long): RateDecision =
    val policies = installed.get.at(point.ordinal)
    policies.length match
      case 0 => RateDecision.Allowed
      case 1 => checkOne(policies(0), keys, nowNanos)
      case _ => checkAll(policies, keys, nowNanos)

  /** Read-only: would a request at `point` be refused? Nothing is charged, so a refused or a passing request costs the key
    * nothing; [[charge]] takes the cost afterwards, for a request that turns out to be a failed attempt. Counts `limited` or
    * `would_limit` for the policies that refuse, and `allowed` when none does.
    */
  def gate(point: Checkpoint, keys: RateLimitKeys, nowNanos: Long): RateDecision =
    val policies = installed.get.at(point.ordinal)
    var worst: Policy = null
    var worstWait = Pass
    var i = 0
    while i < policies.length do
      val policy = policies(i)
      val key = policy.keyOf(keys)
      if !key.eq(Absent) then
        val tracker = policy.tracker
        var entry = tracker.lookup(key, nowNanos)
        var wait = tracker.probe(entry, nowNanos)
        while wait == Dead do
          tracker.evict(key, entry)
          entry = tracker.lookup(key, nowNanos)
          wait = tracker.probe(entry, nowNanos)
        if wait > Pass then
          if policy.policy.mode == GuardMode.Enforce then
            tracker.limited.increment()
            if wait > worstWait then
              worstWait = wait
              worst = policy
          else tracker.wouldLimit.increment()
        else tracker.allowed.increment()
      i += 1
    if worst == null then RateDecision.Allowed else RateDecision.Limited(worst.policy.name, secondsOf(worstWait))

  /** Takes the cost of one request at `point` from every policy that applies, whether or not it would pass: a failed attempt
    * that arrives at an empty bucket changes nothing (the bucket is already as far ahead as it can usefully be). Counts nothing;
    * [[gate]] did.
    */
  def charge(point: Checkpoint, keys: RateLimitKeys, nowNanos: Long): Unit =
    val policies = installed.get.at(point.ordinal)
    var i = 0
    while i < policies.length do
      val policy = policies(i)
      val key = policy.keyOf(keys)
      if !key.eq(Absent) && policy.policy.mode != GuardMode.Off then
        val tracker = policy.tracker
        var entry = tracker.lookup(key, nowNanos)
        var r = tracker.charge(entry, nowNanos)
        while r == Dead do
          tracker.evict(key, entry)
          entry = tracker.lookup(key, nowNanos)
          r = tracker.charge(entry, nowNanos)
      i += 1

  private def checkOne(policy: Policy, keys: RateLimitKeys, now: Long): RateDecision =
    val key = policy.keyOf(keys)
    if key.eq(Absent) then RateDecision.Allowed
    else
      val tracker = policy.tracker
      var entry = tracker.lookup(key, now)
      var retry = tracker.charge(entry, now)
      while retry == Dead do
        tracker.evict(key, entry)
        entry = tracker.lookup(key, now)
        retry = tracker.charge(entry, now)
      if retry == Pass then
        tracker.allowed.increment()
        RateDecision.Allowed
      else if policy.policy.mode == GuardMode.Enforce then
        tracker.limited.increment()
        RateDecision.Limited(policy.policy.name, secondsOf(retry))
      else
        tracker.wouldLimit.increment()
        RateDecision.Allowed

  private def checkAll(policies: Array[Policy], keys: RateLimitKeys, now: Long): RateDecision =
    val n = policies.length
    val trackerKeys = new Array[String](n)
    val entries = new Array[Entry](n)
    val retry = new Array[Long](n)

    // Gate: read-only, every policy. Nothing is charged here.
    var i = 0
    while i < n do
      val key = policies(i).keyOf(keys)
      if !(key.eq(Absent)) then
        val tracker = policies(i).tracker
        trackerKeys(i) = key
        var entry = tracker.lookup(key, now)
        var r = tracker.probe(entry, now)
        while r == Dead do
          tracker.evict(key, entry)
          entry = tracker.lookup(key, now)
          r = tracker.probe(entry, now)
        entries(i) = entry
        retry(i) = r
      else retry(i) = Skipped
      i += 1

    var refusedBy = -1
    i = 0
    while i < n do
      if retry(i) > Pass && policies(i).policy.mode == GuardMode.Enforce && (refusedBy < 0 || retry(i) > retry(refusedBy)) then refusedBy = i
      i += 1
    if refusedBy >= 0 then refuse(policies, retry, refusedBy)
    else
      // Charge: each policy that passed the gate, in order. The charge repeats the check, so a lost race shows here.
      var lost = -1
      i = 0
      while i < n && lost < 0 do
        if retry(i) == Pass then
          val tracker = policies(i).tracker
          var r = tracker.charge(entries(i), now)
          while r == Dead do
            tracker.evict(trackerKeys(i), entries(i))
            entries(i) = tracker.lookup(trackerKeys(i), now)
            r = tracker.charge(entries(i), now)
          if r > Pass then
            retry(i) = r
            if policies(i).policy.mode == GuardMode.Enforce then lost = i
          end if
        end if
        i += 1
      if lost >= 0 then
        // Return what the earlier policies were charged: this request was not admitted.
        var j = 0
        while j < lost do
          if retry(j) == Pass then policies(j).tracker.refund(entries(j))
          j += 1
        refuse(policies, retry, lost)
      else
        i = 0
        while i < n do
          if retry(i) != Skipped then
            if retry(i) == Pass then policies(i).tracker.allowed.increment() else policies(i).tracker.wouldLimit.increment()
          i += 1
        RateDecision.Allowed

  /** Counts the refusal against every enforcing policy that refused, and answers with the one that makes the caller wait
    * longest.
    */
  private def refuse(policies: Array[Policy], retry: Array[Long], worst: Int): RateDecision =
    var i = 0
    while i < policies.length do
      if retry(i) > Pass then
        if policies(i).policy.mode == GuardMode.Enforce then policies(i).tracker.limited.increment()
        else policies(i).tracker.wouldLimit.increment()
      i += 1
    RateDecision.Limited(policies(worst).policy.name, secondsOf(retry(worst)))

  /** Drops every key whose bucket has refilled. */
  def sweep(nowNanos: Long): UIO[Unit] = ZIO.succeed(sweepNow(nowNanos))

  private[http] def sweepNow(nowNanos: Long): Unit = installed.get.all.foreach(_.tracker.sweep(nowNanos))

  /** The counts since the policy was installed (a policy that keeps its buckets across [[update]] keeps its counts). */
  def stats(policy: String): Option[RateLimitStats] =
    installed.get.byName.get(policy).map: p =>
      val t = p.tracker
      RateLimitStats(t.allowed.sum, t.limited.sum, t.wouldLimit.sum, t.overflowHits.sum, t.size)

  /** Moves the counts and gauges into the metrics registry. The hot path only bumps adders; this runs once a second. */
  val publish: UIO[Unit] =
    ZIO.suspendSucceed:
      ZIO.foreachDiscard(installed.get.all.toList): p =>
        val t = p.tracker
        val name = p.policy.name
        val allowed = t.unpublished(t.allowed, t.publishedAllowed)
        val limited = t.unpublished(t.limited, t.publishedLimited)
        val would = t.unpublished(t.wouldLimit, t.publishedWould)
        val overflow = t.unpublished(t.overflowHits, t.publishedOverflow)
        ZIO.when(allowed > 0)(decisions.tagged(labels(name, "allowed")).incrementBy(allowed)) *>
          ZIO.when(limited > 0)(decisions.tagged(labels(name, "limited")).incrementBy(limited)) *>
          ZIO.when(would > 0)(decisions.tagged(labels(name, "would_limit")).incrementBy(would)) *>
          ZIO.when(overflow > 0)(overflowHitsMetric.tagged(MetricLabel("policy", name)).incrementBy(overflow)) *>
          trackedKeys.tagged(MetricLabel("policy", name)).set(t.size.toDouble)

  private def forget(name: String): UIO[Unit] = trackedKeys.tagged(MetricLabel("policy", name)).set(0.0)

  /** One line per policy that refused something (or ran out of room for keys) since the last call, for an operator who reads the
    * log and not the metrics: which policy, how many calls it refused, how many it only counted in shadow. It names no key, so
    * it holds nothing about a caller, and it is one line a minute per policy however hard it is hit, so a flood cannot flood the
    * log. A line is `WARN` when a policy enforced, `INFO` when it only counted.
    */
  private[http] def logSummary(reported: Ref[Map[String, RateLimitStats]], interval: Duration): UIO[Unit] =
    for
      before <- reported.get
      now = installed.get.all.toList.flatMap(p => stats(p.policy.name).map(p.policy.name -> _)).toMap
      _ <- reported.set(now)
      _ <- ZIO.foreachDiscard(now.toList.sortBy(_._1)): (name, total) =>
        // counts restart when a policy is replaced by one with other buckets
        val last = before.get(name).filter(b => b.limited <= total.limited && b.wouldLimit <= total.wouldLimit).getOrElse(RateLimitStats(0, 0, 0, 0, 0))
        val refused = total.limited - last.limited
        val counted = total.wouldLimit - last.wouldLimit
        val overflow = total.overflowHits - last.overflowHits
        val line =
          s"${metricPrefix} rate limit '$name' in the last ${interval.toSeconds} s: refused $refused, would refuse (shadow) $counted, " +
            s"allowed ${total.allowed - last.allowed}, tracked keys ${total.trackedKeys}, keys over the cap $overflow"
        if refused > 0 || overflow > 0 then ZIO.logWarning(line)
        else if counted > 0 then ZIO.logInfo(line)
        else ZIO.unit
    yield ()

  /** Sweeps and publishes once a second, and summarises the refusals once a minute, for the life of the service. */
  val run: UIO[Nothing] =
    Ref.make(Map.empty[String, RateLimitStats]).flatMap: reported =>
      (Clock.nanoTime.flatMap(sweep) *> publish).repeat(Schedule.fixed(1.second)).forever
        .zipPar(ZIO.sleep(SummaryInterval) *> logSummary(reported, SummaryInterval).repeat(Schedule.fixed(SummaryInterval)).forever)
        *> ZIO.never

/** Cumulative counts of one policy; the metrics carry the same numbers. */
final case class RateLimitStats(allowed: Long, limited: Long, wouldLimit: Long, overflowHits: Long, trackedKeys: Int)

object RateLimiter:
  private def labels(policy: String, result: String): Set[MetricLabel] =
    Set(MetricLabel("policy", policy), MetricLabel("result", result))

  // Outcomes of one policy's check, as the nanoseconds to wait: positive is a refusal.
  private[http] final val Pass = 0L
  private[http] final val Dead = -1L
  private final val Skipped = -2L

  // `tat` of a key never seen: far enough back that the bucket is full at any clock reading.
  private final val Fresh = Long.MinValue
  // Marks the first `tat` of an entry that was removed from its map, so a thread still holding it looks the key up again.
  private final val Removed = Long.MaxValue

  private final val NanosPerSecond = 1_000_000_000L
  private final val SweepIntervalNanos = NanosPerSecond
  private[http] val SummaryInterval: Duration = 1.minute

  /** A stand-in for "this request has no value for a dimension the policy needs"; compared by reference. */
  private val Absent: String = new String("absent")

  private def secondsOf(retryNanos: Long): Int =
    math.max(1L, math.min(Int.MaxValue.toLong, (retryNanos + NanosPerSecond - 1) / NanosPerSecond)).toInt

  /** @param metricPrefix the service that owns the limiter: `edge_rate_limit_decisions_total`, `auth_rate_limit_decisions_total` */
  def make(settings: RateLimiterSettings = RateLimiterSettings.default, metricPrefix: String = "edge"): UIO[RateLimiter] =
    ZIO.succeed(new RateLimiter(settings, new AtomicReference(Installed.empty), metricPrefix))

  /** Everything that decides what a bucket is: when this is equal, the buckets of the old policy remain valid. */
  private[http] final case class Shape(
      scope: RateLimitScope,
      by: List[RateLimitDimension],
      limits: List[RateLimitWindow],
      burst: Int,
      cost: Int,
      maxKeys: Int,
      replicas: Int,
  ):
    /** What each pod allows of one window: `limit / replicas` as a longer emission interval, and a burst that is at least the cost. */
    def podBurst: Int = math.max(cost, (burst + replicas - 1) / replicas)

  private[http] object Shape:
    def of(policy: RateLimitPolicy, settings: RateLimiterSettings, replicas: Int = 1): Shape =
      Shape(policy.scope, policy.by, policy.limits, policy.burst, policy.cost, settings.maxKeysPerPolicy, replicas)

  /** The state of one key: a `tat` per window, `Removed` in the first when the entry has left its map. */
  private[http] final class Entry(windows: Int):
    val tat: AtomicLongArray =
      val array = new AtomicLongArray(windows)
      var i = 0
      while i < windows do
        array.set(i, Fresh)
        i += 1
      array

  private[http] final class Policy(val policy: RateLimitPolicy, val tracker: Tracker):
    private val dims = policy.by.toArray
    private val scope = policy.scope

    /** The key of a request for this policy, or [[Absent]] when a value it needs is not known. A key that is one of the
      * request's own values is used as it is; several are joined, each with its length so two different sets can never
      * make the same key.
      */
    def keyOf(keys: RateLimitKeys): String =
      val scopeValue = scope match
        case RateLimitScope.EdgeWide => null
        case RateLimitScope.Resource => keys.resource
        case RateLimitScope.Endpoint => keys.endpoint
      val parts = dims.length + (if scopeValue == null then 0 else 1)
      if parts == 0 then ""
      else if parts == 1 then
        val only = if scopeValue != null then scopeValue else value(keys, dims(0))
        if only.isEmpty then Absent else only
      else
        val sb = new java.lang.StringBuilder(64)
        var ok = true
        if scopeValue != null then
          if scopeValue.isEmpty then ok = false else append(sb, scopeValue)
        var i = 0
        while ok && i < dims.length do
          val v = value(keys, dims(i))
          if v.isEmpty then ok = false else append(sb, v)
          i += 1
        if ok then sb.toString else Absent

    private def append(sb: java.lang.StringBuilder, part: String): Unit =
      sb.append(part.length).append(':').append(part)

    private def value(keys: RateLimitKeys, dim: RateLimitDimension): String = dim match
      case RateLimitDimension.Ip => keys.ip
      case RateLimitDimension.Client => keys.client
      case RateLimitDimension.Subject => keys.subject
      case RateLimitDimension.Session => keys.session
      case RateLimitDimension.Jkt => keys.jkt
      case RateLimitDimension.Certificate => keys.certificate

  /** The buckets of one policy, and its counts. */
  private[http] final class Tracker(val shape: Shape):
    private val windows = shape.limits.size
    // per window: nanoseconds one cost unit takes, and how far `tat` may run ahead of the clock (burst tolerance)
    private val emission = shape.limits.map(w => w.windowSeconds * NanosPerSecond * shape.replicas / w.limit).toArray
    private val increment = emission.map(_ * shape.cost)
    private val tolerance = emission.map(_ * shape.podBurst)

    private val entries = new ConcurrentHashMap[String, Entry]()
    private val count = new AtomicInteger(0)
    private val overflow = new Entry(windows)
    private val lastSweep = new AtomicLong(Long.MinValue)

    val allowed = new LongAdder
    val limited = new LongAdder
    val wouldLimit = new LongAdder
    val overflowHits = new LongAdder

    val publishedAllowed = new AtomicLong
    val publishedLimited = new AtomicLong
    val publishedWould = new AtomicLong
    val publishedOverflow = new AtomicLong

    /** What `counter` has counted since the last time this was asked for it. Two publishers at once never publish a count twice. */
    def unpublished(counter: LongAdder, published: AtomicLong): Long =
      val now = counter.sum
      math.max(0L, now - published.getAndAccumulate(now, math.max))

    def size: Int = count.get

    /** The entry of `key`, creating it while there is room and answering the shared overflow bucket when there is not. */
    def lookup(key: String, now: Long): Entry =
      val found = entries.get(key)
      if found != null then found
      else if count.get < shape.maxKeys || (sweepIfDue(now) && count.get < shape.maxKeys) then
        val fresh = new Entry(windows)
        val raced = entries.putIfAbsent(key, fresh)
        if raced == null then
          count.incrementAndGet()
          fresh
        else raced
      else
        overflowHits.increment()
        overflow

    /** Forgets `entry`, which has been marked removed, so the next lookup creates a fresh one. */
    def evict(key: String, entry: Entry): Unit =
      if entries.remove(key, entry) then count.decrementAndGet()

    /** Whether one window of `entry` lets a request through at `now`: 0 to pass, or the nanoseconds to wait. */
    private def waitOf(tat: Long, now: Long, i: Int): Long =
      val allowAt = math.max(tat, now) + increment(i) - tolerance(i)
      if now >= allowAt then Pass else allowAt - now

    /** Read-only: the longest wait over the windows, [[Pass]] if none, [[Dead]] if the entry was removed. */
    def probe(entry: Entry, now: Long): Long =
      var worst = Pass
      var i = 0
      while i < windows do
        val tat = entry.tat.get(i)
        if i == 0 && tat == Removed then return Dead
        val w = waitOf(tat, now, i)
        if w > worst then worst = w
        i += 1
      worst

    /** Takes the cost from every window, or from none: [[Pass]] when taken, the longest wait when a window refused (what had
      * been taken from earlier windows is returned), [[Dead]] if the entry was removed. Each window is a compare-and-set that
      * repeats its own check, so concurrent callers cannot both take the last token.
      */
    def charge(entry: Entry, now: Long): Long =
      var i = 0
      while i < windows do
        var done = false
        while !done do
          val tat = entry.tat.get(i)
          if i == 0 && tat == Removed then return Dead
          val w = waitOf(tat, now, i)
          if w > Pass then
            var j = 0
            while j < i do
              returnTo(entry, j)
              j += 1
            return w
          if entry.tat.compareAndSet(i, tat, math.max(tat, now) + increment(i)) then done = true
        i += 1
      Pass

    /** Gives back what [[charge]] took. Subtracting commutes with other callers' additions, and a removed entry is gone. */
    def refund(entry: Entry): Unit =
      var i = 0
      while i < windows do
        returnTo(entry, i)
        i += 1

    private def returnTo(entry: Entry, i: Int): Unit =
      var done = false
      while !done do
        val tat = entry.tat.get(i)
        done = (i == 0 && tat == Removed) || entry.tat.compareAndSet(i, tat, tat - increment(i))

    private def idle(entry: Entry, now: Long): Boolean =
      var i = 0
      while i < windows do
        if entry.tat.get(i) > now then return false
        i += 1
      true

    /** Removes the keys whose buckets have refilled. An entry is first marked removed in its first window, by a
      * compare-and-set that fails if anyone charged it meanwhile, so a thread that still holds it looks the key up again
      * rather than charging a bucket nobody will read.
      */
    def sweep(now: Long): Unit =
      val it = entries.entrySet.iterator
      while it.hasNext do
        val e = it.next()
        val entry = e.getValue
        if idle(entry, now) then
          val seen = entry.tat.get(0)
          if seen != Removed && seen <= now && entry.tat.compareAndSet(0, seen, Removed) then
            if entries.remove(e.getKey, entry) then count.decrementAndGet()
      lastSweep.set(now)

    private def sweepIfDue(now: Long): Boolean =
      val last = lastSweep.get
      if (last == Long.MinValue || now - last >= SweepIntervalNanos) && lastSweep.compareAndSet(last, now) then
        sweep(now)
        true
      else false

  /** The policies in force, indexed the ways the hot path needs. */
  private[http] final class Installed(val all: Array[Policy], val byName: Map[String, Policy], byPoint: Array[Array[Policy]]):
    def at(point: Int): Array[Policy] = byPoint(point)

  private[http] object Installed:
    val empty: Installed = of(Nil)

    def of(policies: List[Policy]): Installed =
      val byPoint = Checkpoint.values.map(point => policies.filter(_.policy.checkpoint == point).toArray)
      new Installed(policies.toArray, policies.map(p => p.policy.name -> p).toMap, byPoint)
