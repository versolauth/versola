package versola.antifraud

import zio.*

import java.lang.System
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference, AtomicReferenceArray}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** What a counter counts: one kind of thing a request can be told by. */
enum VelocityKey:
  case phone, ip, device, asn, prefix

/** How far back a count reaches. A window of `n` minutes is the current minute and the `n - 1` before it, so it
  * spans between `n - 1` and `n` minutes of traffic, and the one-minute window is the current clock minute alone. */
enum VelocityWindow(val minutes: Int):
  case OneMinute extends VelocityWindow(1)
  case TenMinutes extends VelocityWindow(10)
  case OneHour extends VelocityWindow(60)
  case OneDay extends VelocityWindow(1440)

/** One minute of one key: the count an instance has seen (`minute` is minutes since the epoch). */
case class VelocityBucket(keyType: VelocityKey, key: String, minute: Long, count: Int)

/** How many requests of a kind (a phone, an address, a device, an ASN, a range of numbers) were seen in the last
  * minute, ten minutes, hour and day.
  *
  * The counts live in memory, per instance, in minute buckets: `record` and `count` take no database and no effect,
  * so they are as cheap as the rules that read them need. Each instance also writes its buckets to Postgres every
  * `flushInterval` and reads the other instances' every `loadInterval` (see [[VelocityCounters.live]]), and a count
  * is what this instance saw plus what the others had written when it last loaded. So a count is
  *   - exact for what this instance saw,
  *   - for the rest stale by up to `flushInterval + loadInterval`: it is accurate to within
  *     `(instances - 1) * rate * (flushInterval + loadInterval)` requests, `rate` being the key's requests per second
  *     at one instance, plus up to `shareFrom - 1` requests per other instance of a key too quiet to be shared yet,
  *   - blind to others altogether while the database is down (it then degrades to a per-instance count, never to an
  *     error or a wait).
  * Memory is bounded: at most `maxKeys` keys of each kind, each at most 1440 buckets of 8 bytes, grown as the key
  * is used and freed when it has been idle for a day. A key beyond the bound is not counted (its count reads as 0, so
  * a rule on it does not fire: the table fails open) and [[untracked]] says how often that happened.
  *
  * A key is spread over `stripes` independent bucket arrays chosen by the thread that records, so many threads on one
  * hot key take different locks; a count sums the stripes.
  */
final class VelocityCounters(settings: VelocityCounters.Settings):
  import VelocityCounters.*

  private val stripes = Integer.highestOneBit(math.max(1, settings.stripes))
  private val tables = VelocityKey.values.map(_ => ConcurrentHashMap[String, Entry]())
  // What the other instances had written when this one last loaded.
  private val remote = AtomicReference[Remote](Remote.empty)
  private val untrackedCount = AtomicLong(0)

  private def table(keyType: VelocityKey) = tables(keyType.ordinal)

  private def minuteOf(at: Instant): Long = Math.floorDiv(at.getEpochSecond, 60L)

  private def normalize(key: String): String = if key.length > maxKeyLength then key.substring(0, maxKeyLength) else key

  /** Counts one request of `key` at `at`. Never blocks beyond a few instructions of lock on a stripe nobody else
    * is likely on, and does nothing for a new key once `maxKeys` are tracked. */
  def record(keyType: VelocityKey, key: String, at: Instant): Unit = add(keyType, key, at, 1)

  private def add(keyType: VelocityKey, key: String, at: Instant, n: Int): Unit =
    val k = normalize(key)
    val map = table(keyType)
    val existing = map.get(k)
    val entry =
      if existing != null then existing
      else if map.size >= settings.maxKeys then null
      else map.computeIfAbsent(k, _ => Entry(stripes))
    if entry == null then untrackedCount.incrementAndGet()
    else entry.add(minuteOf(at), n, (Thread.currentThread().threadId() & (stripes - 1)).toInt)

  /** How many requests of `key` were seen in `window` as of `at`, here and (as last loaded) elsewhere. */
  def count(keyType: VelocityKey, key: String, window: VelocityWindow, at: Instant): Int =
    val k = normalize(key)
    val now = minuteOf(at)
    val from = now - window.minutes + 1
    val local = Option(table(keyType).get(k)).fold(0L)(_.count(from, now))
    val elsewhere = remote.get.count(keyType, k, from, now)
    math.min(local + elsewhere, Int.MaxValue.toLong).toInt

  /** What the evaluate caller calls with each risky step: counts it against its keys and puts the counts in the
    * context for [[VelocityRules]] (see there for what is counted). A no-op while the counters are off. */
  def recordAndSnapshot(ctx: AntifraudContext): UIO[AntifraudContext] =
    if !settings.enabled then ZIO.succeed(ctx)
    else Clock.instant.map(VelocityRules.recordAndSnapshot(this, settings, ctx, _))

  /** Keys not counted because `maxKeys` were tracked already, since the start. */
  def untracked: Long = untrackedCount.get

  def size: Int = tables.map(_.size).sum

  /** Forgets keys that have been quiet for the whole day, and gives back the memory of old buckets of the others. */
  def evict(at: Instant): Unit =
    val now = minuteOf(at)
    tables.foreach: map =>
      map.entrySet().removeIf: entry =>
        val e = entry.getValue
        e.trim(now)
        e.lastMinute < now - VelocityWindow.OneDay.minutes + 1 && e.isEmpty

  /** The minutes that changed since the last call, as this instance's own totals for them, for [[VelocityRepository]]:
    * at most `limit` of them, the rest stays for the next call. A key is left out until `shareFrom` requests were
    * seen of it in the day. The totals are absolute (the repository overwrites), so a call whose result was not
    * written is made good by [[unflushed]]. */
  def drain(at: Instant, limit: Int): List[(VelocityBucket, Long)] =
    val now = minuteOf(at)
    val out = ArrayBuffer.empty[(VelocityBucket, Long)]
    val iterators = VelocityKey.values.iterator
    while iterators.hasNext && out.size < limit do
      val keyType = iterators.next()
      val it = table(keyType).entrySet().iterator()
      while it.hasNext && out.size < limit do
        val e = it.next()
        val entry = e.getValue
        if entry.dirtySince.get != Long.MaxValue && entry.count(now - 1439, now) >= settings.shareFrom then
          val since = entry.dirtySince.getAndSet(Long.MaxValue)
          if since != Long.MaxValue then
            entry.minutes(math.max(since, now - 1439), now).foreach: (minute, count) =>
              out += ((VelocityBucket(keyType, e.getKey, minute, count), since))
    out.toList

  /** Marks what [[drain]] returned as changed again, because it was not written. */
  def unflushed(drained: List[(VelocityBucket, Long)]): Unit =
    drained.foreach: (bucket, since) =>
      Option(table(bucket.keyType).get(bucket.key)).foreach(_.dirtySince.accumulateAndGet(since, math.min))

  /** Replaces what is known of the other instances with `buckets`, as loaded from the database. */
  def loaded(buckets: List[VelocityBucket]): Unit =
    val byKey = buckets.groupBy(b => (b.keyType, b.key)).view.mapValues(bs => bs.sortBy(_.minute).map(b => pack(b.minute, b.count)).toArray).toMap
    remote.set(Remote(byKey))

object VelocityCounters:

  /** @param enabled       off by default: nothing is counted, nothing is flushed, no rule fires
    * @param maxKeys       keys tracked at once of each kind; beyond that new keys are not counted (fail open)
    * @param stripes       independent bucket arrays per key, a power of two (rounded down to one); hot keys contend less
    * @param flushInterval how often this instance's changed buckets are written to the database
    * @param loadInterval  how often the other instances' buckets are read back
    * @param shareFrom     requests of a key in the day before this instance shares it with the others; bounds what the
    *                      database holds (and what a load reads) to the keys that have any weight
    * @param maxFlushRows  buckets written per flush; the rest follows at the next
    * @param maxLoadRows   buckets read per load; keys beyond that are left to the per-instance count
    * @param thresholds    the limits the rules apply (see [[VelocityRules]]); none by default
    */
  case class Settings(
      enabled: Boolean = false,
      maxKeys: Int = 20000,
      stripes: Int = 8,
      flushInterval: Duration = 10.seconds,
      loadInterval: Duration = 20.seconds,
      shareFrom: Int = 3,
      maxFlushRows: Int = 20000,
      maxLoadRows: Int = 200000,
      thresholds: VelocityRules.Thresholds = VelocityRules.Thresholds(),
  )

  /** Longest key kept; longer ones are cut, so the attacker's choice of length buys no memory. */
  val maxKeyLength = 128

  val off: VelocityCounters = VelocityCounters(Settings())

  private def pack(minute: Long, count: Int): Long = (minute << 32) | (count.toLong & 0xffffffffL)
  private def minuteOf(packed: Long): Long = packed >>> 32
  private def countOf(packed: Long): Int = (packed & 0xffffffffL).toInt

  /** What the other instances wrote: per key, packed (minute, count) sorted by minute. */
  private final class Remote(byKey: Map[(VelocityKey, String), Array[Long]]):
    def count(keyType: VelocityKey, key: String, from: Long, to: Long): Long =
      byKey.get((keyType, key)) match
        case None => 0L
        case Some(buckets) =>
          var sum = 0L
          var i = buckets.length - 1
          while i >= 0 && minuteOf(buckets(i)) >= from do
            if minuteOf(buckets(i)) <= to then sum += countOf(buckets(i))
            i -= 1
          sum

  private object Remote:
    val empty: Remote = Remote(Map.empty)

  /** One key: `stripes` bucket arrays, made when first used. */
  private final class Entry(stripes: Int):
    private val parts = AtomicReferenceArray[Buckets](stripes)
    private val last = AtomicLong(Long.MinValue)

    /** The oldest minute changed since the last drain, or `Long.MaxValue` while nothing is. */
    val dirtySince = AtomicLong(Long.MaxValue)

    def lastMinute: Long = last.get

    def add(minute: Long, n: Int, stripe: Int): Unit =
      var part = parts.get(stripe)
      if part == null then
        parts.compareAndSet(stripe, null, Buckets())
        part = parts.get(stripe)
      part.add(minute, n)
      // The usual record is a minute already known: two reads, no write.
      if minute > last.get then last.accumulateAndGet(minute, math.max)
      if minute < dirtySince.get then dirtySince.accumulateAndGet(minute, math.min)

    def count(from: Long, to: Long): Long =
      var sum = 0L
      var i = 0
      while i < stripes do
        val part = parts.get(i)
        if part != null then sum += part.count(from, to)
        i += 1
      sum

    /** Totals per minute over all stripes. */
    def minutes(from: Long, to: Long): List[(Long, Int)] =
      val totals = scala.collection.mutable.TreeMap.empty[Long, Long]
      var i = 0
      while i < stripes do
        val part = parts.get(i)
        if part != null then part.foreach(from, to)((minute, count) => totals.update(minute, totals.getOrElse(minute, 0L) + count))
        i += 1
      totals.iterator.map((minute, count) => (minute, math.min(count, Int.MaxValue.toLong).toInt)).toList

    def trim(now: Long): Unit =
      var i = 0
      while i < stripes do
        val part = parts.get(i)
        if part != null then part.trim(now - 1439)
        i += 1

    def isEmpty: Boolean = count(Long.MinValue, Long.MaxValue) == 0L

  /** The minutes of one stripe, oldest first, as packed (minute, count) longs: a key seen in one minute holds one. */
  private final class Buckets:
    private var data = new Array[Long](2)
    private var size = 0

    /** Index of the last bucket not after `minute`, -1 if there is none. Requests arrive in time order but for clock
      * jitter and late callers, so this is the end of the array in the usual case and a short walk back otherwise. */
    private def lastAtOrBefore(minute: Long): Int =
      var i = size - 1
      while i >= 0 && minuteOf(data(i)) > minute do i -= 1
      i

    def add(minute: Long, n: Int): Unit = synchronized:
      var i = lastAtOrBefore(minute)
      if i >= 0 && minuteOf(data(i)) == minute then data(i) = pack(minute, saturate(countOf(data(i)), n))
      else
        if size == data.length then
          // Old minutes go before the array grows, so a key that is busy all day stays within 1440.
          trimLocked(minute - 1439)
          i = lastAtOrBefore(minute)
          if size == data.length then data = java.util.Arrays.copyOf(data, data.length * 2)
        // `i` is the last minute before this one: make room after it.
        val at = i + 1
        System.arraycopy(data, at, data, at + 1, size - at)
        data(at) = pack(minute, n)
        size += 1

    private def saturate(have: Int, n: Int): Int = math.min(have.toLong + n, Int.MaxValue.toLong).toInt

    def count(from: Long, to: Long): Long = synchronized:
      var sum = 0L
      var i = size - 1
      while i >= 0 && minuteOf(data(i)) >= from do
        if minuteOf(data(i)) <= to then sum += countOf(data(i))
        i -= 1
      sum

    def foreach(from: Long, to: Long)(f: (Long, Int) => Unit): Unit = synchronized:
      var i = size - 1
      while i >= 0 && minuteOf(data(i)) >= from do
        if minuteOf(data(i)) <= to then f(minuteOf(data(i)), countOf(data(i)))
        i -= 1

    def trim(from: Long): Unit = synchronized:
      trimLocked(from)
      if size > 0 && size * 4 < data.length && data.length > 2 then data = java.util.Arrays.copyOf(data, math.max(2, size * 2))

    private def trimLocked(from: Long): Unit =
      var drop = 0
      while drop < size && minuteOf(data(drop)) < from do drop += 1
      if drop > 0 then
        System.arraycopy(data, drop, data, 0, size - drop)
        size -= drop

  /** Starts the flush and the load on fibers of their own, and the eviction; all stop with the scope. A failure of
    * the database is counted and logged once per outage, and leaves the counts per instance until it is back.
    * With [[Settings.enabled]] false nothing starts and the counters stay empty. */
  val live: ZLayer[VelocityRepository & Settings & Scope, Nothing, VelocityCounters] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[VelocityRepository]
        settings <- ZIO.service[Settings]
        counters = if settings.enabled then VelocityCounters(settings) else off
        _ <- ZIO.when(settings.enabled):
          for
            instance <- ZIO.succeed(UUID.randomUUID())
            failing <- Ref.make(false)
            reported <- Ref.make(0L)
            _ <- Flusher(counters, repository, settings, instance, failing, reported).run
            _ <- (ZIO.sleep(10.minutes) *> Clock.instant.map(counters.evict)).forever.forkScoped
          yield ()
      yield counters

  private class Flusher(
      counters: VelocityCounters,
      repository: VelocityRepository,
      settings: Settings,
      instance: UUID,
      failing: Ref[Boolean],
      reported: Ref[Long],
  ):
    // A failure is swallowed on purpose (the counts stay per instance), so the first of an outage is logged with its
    // stack; the counter says how long it lasts.
    private def failed(what: String, cause: Cause[Throwable]): UIO[Unit] =
      AntifraudMetrics.velocityFailed(what) *>
        failing.getAndSet(true).flatMap(was => ZIO.logWarningCause(s"antifraud velocity $what failed", cause).unless(was)).unit

    private val recovered: UIO[Unit] = failing.set(false)

    private def reportSize: UIO[Unit] =
      reported.getAndSet(counters.untracked).flatMap(was => AntifraudMetrics.velocityUntracked(counters.untracked - was)) *>
        AntifraudMetrics.velocityKeys(counters.size)

    private val flush: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val drained = counters.drain(now, settings.maxFlushRows)
        ZIO.when(drained.nonEmpty):
          repository.save(instance, drained.map(_._1)).foldCauseZIO(
            cause => ZIO.succeed(counters.unflushed(drained)) *> failed("flush", cause),
            _ => AntifraudMetrics.velocityFlushed(drained.size) *> recovered,
          )
        .unit

    private val load: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val from = Math.floorDiv(now.getEpochSecond, 60L) - VelocityWindow.OneDay.minutes + 1
        (repository.loadOthers(instance, from, settings.maxLoadRows) <*
          repository.prune(from - 60)).foldCauseZIO(
          cause => failed("load", cause),
          buckets => ZIO.succeed(counters.loaded(buckets)) *> reportSize *> recovered,
        )

    val run: URIO[Scope, Unit] =
      for
        // Not `fork`: that would tie the fibers to the one that builds the layer, which ends once it is built.
        _ <- (ZIO.sleep(settings.flushInterval) *> flush).forever.forkScoped
        _ <- (load *> ZIO.sleep(settings.loadInterval)).forever.forkScoped
        // What is written last is written once more on the way out, so a deploy loses no more than the interval.
        _ <- ZIO.addFinalizer(flush.timeout(5.seconds).unit)
      yield ()
