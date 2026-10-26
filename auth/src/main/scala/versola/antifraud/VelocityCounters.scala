package versola.antifraud

import versola.util.{Base64Url, CoreConfig, Secret, SecurityService}
import zio.*

import java.lang.System
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReferenceArray}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** What a counter counts: one kind of thing a request can be told by. */
enum VelocityKey:
  case phone, ip, device, asn, prefix

/** How far back a count reaches. A window of `n` minutes is the current minute and the `n - 1` before it, so it
  * spans between `n - 1` and `n` minutes of traffic, and the one-minute window is the current clock minute alone: a
  * burst that straddles a minute boundary is split in two, so up to about twice a one-minute limit can pass. */
enum VelocityWindow(val minutes: Int):
  case OneMinute extends VelocityWindow(1)
  case TenMinutes extends VelocityWindow(10)
  case OneHour extends VelocityWindow(60)
  case OneDay extends VelocityWindow(1440)

/** One minute of one key: the count an instance has seen (`minute` is minutes since the epoch). */
case class VelocityBucket(keyType: VelocityKey, key: String, minute: Long, count: Int)

/** A bucket as an instance wrote it. */
case class SharedBucket(instance: UUID, bucket: VelocityBucket)

/** How many requests of a kind (a phone, an address, a device, an ASN, a range of numbers) were seen in the last
  * minute, ten minutes, hour and day. It counts requests, not distinct values: how many different phones one device,
  * address or ASN asked for needs the device identity of P1-1 and is the next thing to build on this.
  *
  * The counts live in memory, per instance, in minute buckets: `record` and `count` take no database and no effect,
  * so they are as cheap as the rules that read them need. Each instance also writes its buckets to Postgres every
  * `flushInterval` and reads what the other instances changed every `loadInterval` (see [[VelocityCounters.live]]),
  * and a count is what this instance saw plus what the others had written when it last loaded. So a count is
  *   - exact for what this instance saw,
  *   - for the rest stale by up to `flushInterval + loadInterval`: it is accurate to within
  *     `(instances - 1) * rate * (flushInterval + loadInterval)` requests, `rate` being the key's requests per second
  *     at one instance, plus up to `shareFrom - 1` requests per other instance of a key too quiet to be shared yet,
  *   - blind to others altogether while the database is down (it then degrades to a per-instance count, never to an
  *     error or a wait).
  * Memory is bounded: at most `maxKeys` keys of each kind, each at most 1440 buckets of 8 bytes in each of its stripes,
  * grown as the key is used and freed when it has been idle for a day. Keys are attacker-chosen (addresses, numbers), so a full table
  * does not switch the detection off: a new key takes the place of the oldest key that was seen once and never again
  * (junk, by the look of it), and only when there is none of those is the new key not counted, which fails open (its
  * count reads 0, its rule does not fire). Both are counted ([[evicted]], [[untracked]]).
  *
  * A key is spread over `stripes` independent bucket arrays chosen by the thread that records, so many threads on one
  * hot key take different locks; a count sums the stripes.
  *
  * @param ipKey how a network (see [[VelocityRules.network]]) becomes the key it is counted and stored under: a keyed hash
  *              in the service (see [[live]]), the network itself in tests; `None` when it cannot be made
  */
final class VelocityCounters(settings: VelocityCounters.Settings, ipKey: String => UIO[Option[String]]):
  import VelocityCounters.*

  private val stripes = Integer.highestOneBit(math.max(1, settings.stripes))
  private val tables = VelocityKey.values.map(_ => ConcurrentHashMap[String, Entry]())
  // Keys in the order they were admitted, to find the junk among them when the table is full.
  private val admitted = VelocityKey.values.map(_ => ConcurrentLinkedQueue[Admitted]())
  private val remote = Remote(settings.maxKeys * VelocityKey.values.length)
  private val untrackedCount = AtomicLong(0)
  private val evictedCount = AtomicLong(0)

  // Not Thread.threadId (JDK 19 and later) nor getId (deprecated there): the identity hash is on every JDK, and any
  // spread over the stripes will do.
  private def stripeOfThread: Int =
    val h = System.identityHashCode(Thread.currentThread())
    (h ^ (h >>> 16)) & (stripes - 1)

  private def table(keyType: VelocityKey) = tables(keyType.ordinal)

  private def minuteOf(at: Instant): Long = Math.floorDiv(at.getEpochSecond, 60L)

  private def normalize(key: String): String = if key.length > maxKeyLength then key.substring(0, maxKeyLength) else key

  /** Counts one request of `key` at `at`. Takes a lock of a stripe nobody else is likely on for a few instructions,
    * and does nothing for a new key when `maxKeys` are tracked and none of them is junk. */
  def record(keyType: VelocityKey, key: String, at: Instant): Unit =
    val k = normalize(key)
    val map = table(keyType)
    val existing = map.get(k)
    val entry = if existing != null then existing else admit(keyType, map, k)
    if entry == null then untrackedCount.incrementAndGet()
    else entry.add(minuteOf(at), 1, stripeOfThread)

  private def admit(keyType: VelocityKey, map: ConcurrentHashMap[String, Entry], key: String): Entry | Null =
    if map.size >= settings.maxKeys && !evictJunk(keyType, map) then null
    else
      val entry = map.computeIfAbsent(key, _ => Entry(stripes))
      admitted(keyType.ordinal).add(Admitted(key, entry))
      entry

  /** Makes room by dropping the oldest key that has been seen once. A few of the oldest are looked at, so that a
    * request never spends long here; a key seen twice is established and leaves the queue. */
  private def evictJunk(keyType: VelocityKey, map: ConcurrentHashMap[String, Entry]): Boolean =
    val queue = admitted(keyType.ordinal)
    var tries = 0
    var made = false
    while !made && tries < 16 do
      tries += 1
      queue.poll() match
        case null => tries = Int.MaxValue
        // An entry seen again meanwhile is established, and is no longer a candidate: it is not put back.
        case Admitted(key, entry) =>
          if entry.junk && map.remove(key, entry) then
            evictedCount.incrementAndGet()
            made = true
    made

  /** How many requests of `key` were seen in `window` as of `at`, here and (as last loaded) elsewhere. */
  def count(keyType: VelocityKey, key: String, window: VelocityWindow, at: Instant): Int =
    val k = normalize(key)
    val now = minuteOf(at)
    val from = now - window.minutes + 1
    val local = Option(table(keyType).get(k)).fold(0L)(_.count(from, now))
    val elsewhere = remote.count(keyType, k, from, now)
    math.min(local + elsewhere, Int.MaxValue.toLong).toInt

  /** The keys a context is told by (see [[VelocityRules.keys]]); the address is made into its network key here. */
  private def keysOf(ctx: AntifraudContext): UIO[List[(VelocityKey, String)]] =
    ZIO.foreach(ctx.ip.flatMap(VelocityRules.network).toList)(ipKey).map(ips => VelocityRules.keys(ctx, ips.flatten.headOption))

  /** Counts a risky step against its keys. For a caller that decides first and counts only what it lets through (see
    * [[snapshot]]): a request that was refused, or one against somebody else's number, should not raise the
    * count that is then used against that number's owner. No-op while the counters are off. */
  def record(ctx: AntifraudContext): UIO[Unit] =
    if !settings.enabled then ZIO.unit
    else
      for
        at <- Clock.instant
        keys <- keysOf(ctx)
      yield keys.foreach((keyType, key) => record(keyType, key, at))

  /** Puts the counts of the step's keys in the context for [[VelocityRules]], this request counted in them as if it
    * were recorded (so a limit of N is exceeded by the N+1st), without recording it. No-op while the counters are off. */
  def snapshot(ctx: AntifraudContext): UIO[AntifraudContext] = signals(ctx, 1)

  /** [[record]] and then [[snapshot]]'s counts, for a caller that counts every step it sees. */
  def recordAndSnapshot(ctx: AntifraudContext): UIO[AntifraudContext] =
    record(ctx) *> signals(ctx, 0)

  private def signals(ctx: AntifraudContext, extra: Int): UIO[AntifraudContext] =
    if !settings.enabled then ZIO.succeed(ctx)
    else
      for
        at <- Clock.instant
        keys <- keysOf(ctx)
      yield VelocityRules.withSignals(this, settings, ctx, keys, at, extra)

  /** Keys not counted because `maxKeys` were tracked and none could be made room for, since the start. More than 0 is
    * itself a sign of an attack (or of a `max-keys` that is too small for the traffic). */
  def untracked: Long = untrackedCount.get

  /** Keys dropped while the table was full to make room for a new one, since the start. */
  def evicted: Long = evictedCount.get

  def size: Int = tables.map(_.size).sum

  /** Forgets keys that have been quiet for the whole day, and gives back the memory of old buckets of the others. */
  def evict(at: Instant): Unit =
    val now = minuteOf(at)
    tables.zip(admitted).foreach: (map, queue) =>
      map.entrySet().removeIf: entry =>
        val e = entry.getValue
        e.trim(now)
        e.lastMinute < now - VelocityWindow.OneDay.minutes + 1 && e.isEmpty
      // What is gone or established is no use in the queue.
      queue.removeIf(a => (map.get(a.key).ne(a.entry)) || !a.entry.junk)
    remote.trim(now)

  /** The minutes that changed since the last call, as this instance's own totals for them, for [[VelocityRepository]]:
    * at most `limit` of them (never more), the busiest keys first, so that a flood of junk keys cannot crowd out the
    * keys that matter. What does not fit stays for the next call. A key is left out until `shareFrom` requests were
    * seen of it in the day. The totals are absolute (the repository overwrites), so a call whose result was not
    * written is made good by [[unflushed]]. */
  def drain(at: Instant, limit: Int): List[(VelocityBucket, Long)] =
    val now = minuteOf(at)
    val from = now - 1439
    val candidates =
      for
        keyType <- VelocityKey.values.toList
        e <- table(keyType).entrySet().iterator().asScala
        entry = e.getValue
        if entry.dirtySince.get != Long.MaxValue
        total = entry.count(from, now)
        if total >= settings.shareFrom
      yield (keyType, e.getKey, entry, total)
    val out = ArrayBuffer.empty[(VelocityBucket, Long)]
    candidates.sortBy(-_._4).foreach: (keyType, key, entry, _) =>
      if out.size < limit then
        val since = entry.dirtySince.getAndSet(Long.MaxValue)
        if since != Long.MaxValue then
          val rows = entry.minutes(math.max(since, from), now)
          if out.size + rows.size <= limit then rows.foreach((minute, count) => out += ((VelocityBucket(keyType, key, minute, count), since)))
          else entry.dirtySince.accumulateAndGet(since, math.min)
    out.toList

  /** Marks what [[drain]] returned as changed again, because it was not written. */
  def unflushed(drained: List[(VelocityBucket, Long)]): Unit =
    drained.foreach: (bucket, since) =>
      Option(table(bucket.keyType).get(bucket.key)).foreach(_.dirtySince.accumulateAndGet(since, math.min))

  /** Takes in what other instances wrote, as loaded from the database: each bucket replaces what that instance
    * wrote for the minute before, so reading the same rows again changes nothing. */
  def loaded(buckets: List[SharedBucket]): Unit = remote.put(buckets)

  /** Keys of other instances held, for tests and the gauge. */
  def remoteSize: Int = remote.size

object VelocityCounters:

  /** @param enabled       off by default: nothing is counted, nothing is flushed, no rule fires
    * @param maxKeys       keys tracked at once of each kind; beyond that a new key takes the place of a key seen once, or
    *                      is not counted when there is none (fail open)
    * @param stripes       independent bucket arrays per key, a power of two (rounded down to one); hot keys contend less
    * @param flushInterval how often this instance's changed buckets are written to the database
    * @param loadInterval  how often what the other instances changed is read back
    * @param shareFrom     requests of a key in the day before this instance shares it with the others; bounds what the
    *                      database holds (and what a load reads) to the keys that have any weight
    * @param maxFlushRows  buckets written per flush, at most; the busiest keys first, the rest follows at the next
    * @param maxLoadKeys   keys read per load, the busiest first; the others are left to the per-instance count
    * @param thresholds    the limits the rules apply (see [[VelocityRules]]); none by default
    */
  case class Settings(
      enabled: Boolean,
      maxKeys: Int,
      stripes: Int,
      flushInterval: Duration,
      loadInterval: Duration,
      shareFrom: Int,
      maxFlushRows: Int,
      maxLoadKeys: Int,
      thresholds: VelocityRules.Thresholds,
  )

  /** Longest key kept; longer ones are cut, so the attacker's choice of length buys no memory. */
  val maxKeyLength = 128

  /** Counters that key an address by its network as it is: for tests, and where nothing is stored. */
  def apply(settings: Settings): VelocityCounters = new VelocityCounters(settings, network => ZIO.succeed(Some(network)))

  private def pack(minute: Long, count: Int): Long = (minute << 32) | (count.toLong & 0xffffffffL)
  private def minuteOf(packed: Long): Long = packed >>> 32
  private def countOf(packed: Long): Int = (packed & 0xffffffffL).toInt

  private case class Admitted(key: String, entry: Entry)

  /** What the other instances wrote: per key, each instance's buckets. Holds at most `maxKeys` keys, so a flood of keys
    * at another instance cannot grow this one without bound. */
  private final class Remote(maxKeys: Int):
    private val byKey = ConcurrentHashMap[(VelocityKey, String), mutable.HashMap[UUID, Buckets]]()

    def size: Int = byKey.size

    def put(buckets: List[SharedBucket]): Unit =
      buckets.foreach: shared =>
        val id = (shared.bucket.keyType, shared.bucket.key)
        val key = byKey.get(id)
        val instances =
          if key != null then key
          else if byKey.size >= maxKeys then null
          else byKey.computeIfAbsent(id, _ => mutable.HashMap.empty)
        if instances != null then
          val mine = instances.synchronized(instances.getOrElseUpdate(shared.instance, Buckets()))
          mine.set(shared.bucket.minute, shared.bucket.count)

    def count(keyType: VelocityKey, key: String, from: Long, to: Long): Long =
      val instances = byKey.get((keyType, key))
      if instances == null then 0L
      else instances.synchronized(instances.values.toList).foldLeft(0L)(_ + _.count(from, to))

    def trim(now: Long): Unit =
      byKey.entrySet().removeIf: entry =>
        val instances = entry.getValue
        instances.synchronized:
          instances.values.foreach(_.trim(now - 1439))
          instances.filterInPlace((_, buckets) => !buckets.isEmpty)
          instances.isEmpty

  /** One key: `stripes` bucket arrays, made when first used. */
  private final class Entry(stripes: Int):
    private val parts = AtomicReferenceArray[Buckets](stripes)
    private val last = AtomicLong(Long.MinValue)
    // Requests seen, up to 2: all that is needed to tell a key seen once from the rest. Not counted further, so the
    // hottest key has no counter for its threads to fight over.
    private val seen = AtomicInteger(0)

    /** The oldest minute changed since the last drain, or `Long.MaxValue` while nothing is. */
    val dirtySince = AtomicLong(Long.MaxValue)

    def lastMinute: Long = last.get

    /** Seen once, so far: what an attacker's throwaway key looks like. */
    def junk: Boolean = seen.get <= 1

    def add(minute: Long, n: Int, stripe: Int): Unit =
      var part = parts.get(stripe)
      if part == null then
        parts.compareAndSet(stripe, null, Buckets())
        part = parts.get(stripe)
      part.add(minute, n)
      if seen.get < 2 then seen.incrementAndGet()
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
      val totals = mutable.TreeMap.empty[Long, Long]
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

    def add(minute: Long, n: Int): Unit = put(minute, n, replace = false)

    /** Makes the count of `minute` exactly `count`. */
    def set(minute: Long, count: Int): Unit = put(minute, count, replace = true)

    private def put(minute: Long, n: Int, replace: Boolean): Unit = synchronized:
      var i = lastAtOrBefore(minute)
      if i >= 0 && minuteOf(data(i)) == minute then data(i) = pack(minute, if replace then n else saturate(countOf(data(i)), n))
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

    def isEmpty: Boolean = synchronized(size == 0)

    def trim(from: Long): Unit = synchronized:
      trimLocked(from)
      if size > 0 && size * 4 < data.length && data.length > 2 then data = java.util.Arrays.copyOf(data, math.max(2, size * 2))

    private def trimLocked(from: Long): Unit =
      var drop = 0
      while drop < size && minuteOf(data(drop)) < from do drop += 1
      if drop > 0 then
        System.arraycopy(data, drop, data, 0, size - drop)
        size -= drop

  /** Starts the flush, the load and the upkeep on fibers of their own; all stop with the scope. A failure of the
    * database is counted and logged once per outage, and leaves the counts per instance until it is back. With
    * [[Settings.enabled]] false nothing starts and the counters stay empty.
    *
    * An address is stored as a keyed hash of its network (`/32` for IPv4, `/64` for IPv6), made with a key derived from
    * the antifraud subject secret under a label of its own, so the table holds no address, and the hash cannot be
    * matched against one made anywhere else with the same secret. */
  val live: ZLayer[VelocityRepository & Settings & SecurityService & CoreConfig & Scope, Nothing, VelocityCounters] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[VelocityRepository]
        settings <- ZIO.service[Settings]
        securityService <- ZIO.service[SecurityService]
        config <- ZIO.service[CoreConfig]
        rootKey = config.security.antifraudSubjectSecret.getOrElse(config.security.sessionsSecret)
        key <- securityService.mac(Secret("versola/antifraud/velocity-ip/v1".getBytes(StandardCharsets.UTF_8)), rootKey).map(Secret(_)).orDie
        // A hash that cannot be made leaves the request without an address key: the rule on it does not fire (fail open).
        hash = (network: String) =>
          securityService.mac(Secret(network.getBytes(StandardCharsets.UTF_8)), key).map(bytes =>
            Some(Base64Url.encode(bytes): String),
          ).orElseSucceed(None)
        counters <-
          if settings.enabled then
            for
              counters <- ZIO.succeed(new VelocityCounters(settings, hash))
              instance <- ZIO.succeed(UUID.randomUUID())
              failing <- Ref.make(false)
              reported <- ZIO.foreach(List.fill(2)(0L))(Ref.make(_))
              _ <- Flusher(counters, repository, settings, instance, failing, reported(0), reported(1)).run
            yield counters
          else ZIO.succeed(new VelocityCounters(settings, hash))
      yield counters

  /** Rows deleted per statement of the upkeep. */
  private val PruneBatch = 10000

  private class Flusher(
      counters: VelocityCounters,
      repository: VelocityRepository,
      settings: Settings,
      instance: UUID,
      failing: Ref[Boolean],
      reportedUntracked: Ref[Long],
      reportedEvicted: Ref[Long],
  ):
    // A failure is swallowed on purpose (the counts stay per instance), so the first of an outage is logged with its
    // stack; the counter says how long it lasts.
    private def failed(what: String, cause: Cause[Throwable]): UIO[Unit] =
      AntifraudMetrics.velocityFailed(what) *>
        failing.getAndSet(true).flatMap(was => ZIO.logWarningCause(s"antifraud velocity $what failed", cause).unless(was)).unit

    private val recovered: UIO[Unit] = failing.set(false)

    private def delta(reported: Ref[Long], total: Long): UIO[Long] = reported.getAndSet(total).map(total - _)

    private val report: UIO[Unit] =
      delta(reportedUntracked, counters.untracked).flatMap(AntifraudMetrics.velocityUntracked) *>
        delta(reportedEvicted, counters.evicted).flatMap(AntifraudMetrics.velocityEvicted) *>
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

    // A load reads a range of minutes, never the day in one query. The recent minutes (the only ones a flush on
    // time can still change) are read every time, and one hour of the older ones with them, in turn, so that what a
    // late flush wrote (a database that was down) is found within `Slices` loads. The first load goes through all
    // the slices at once. Rows are absolute, so reading one twice is harmless.
    // What can have changed since the previous load: a flush and a load interval, with room for the minute boundary.
    private val RecentMinutes = math.max(3L, (settings.flushInterval + settings.loadInterval).toMinutes + 2)
    private val SliceMinutes = 60L
    private val Slices = 24

    private def loadRange(from: Long, to: Long): UIO[Boolean] =
      repository.loadOthers(instance, from, to, settings.maxLoadKeys).foldCauseZIO(
        cause => failed("load", cause).as(false),
        buckets => ZIO.succeed(counters.loaded(buckets)).as(true),
      )

    private def load(slice: Ref[Int], first: Ref[Boolean]): UIO[Unit] =
      for
        now <- Clock.instant.map(at => Math.floorDiv(at.getEpochSecond, 60L))
        dayStart = now - VelocityWindow.OneDay.minutes + 1
        recentFrom = now - RecentMinutes
        isFirst <- first.getAndSet(false)
        n <- slice.getAndUpdate(i => (i + 1) % Slices)
        older = (if isFirst then 0 until Slices else n to n).toList
        recent <- loadRange(recentFrom, Long.MaxValue)
        // Stops at the first failure: a database that is down is asked once per interval, not once per slice.
        ok <- ZIO.foldLeft(older)(recent): (ok, i) =>
          val to = recentFrom - SliceMinutes * i
          if !ok || to <= dayStart then ZIO.succeed(ok) else loadRange(math.max(dayStart, to - SliceMinutes), to)
        _ <- if ok then report *> recovered else ZIO.unit
      yield ()

    // Rows older than the day are deleted here, every ten minutes, a batch at a time with a rest between, and only by
    // the instance that gets the lock.
    private val upkeep: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val before = Math.floorDiv(now.getEpochSecond, 60L) - VelocityWindow.OneDay.minutes - 59
        def batches(left: Int): Task[Unit] =
          repository.prune(before, PruneBatch).flatMap: deleted =>
            if deleted >= PruneBatch && left > 1 then ZIO.sleep(200.millis) *> batches(left - 1) else ZIO.unit
        ZIO.succeed(counters.evict(now)) *> batches(100).foldCauseZIO(cause => failed("prune", cause), _ => ZIO.unit)

    val run: URIO[Scope, Unit] =
      for
        // Not `fork`: that would tie the fibers to the one that builds the layer, which ends once it is built.
        _ <- (ZIO.sleep(settings.flushInterval) *> flush).forever.forkScoped
        slice <- Ref.make(0)
        first <- Ref.make(true)
        // The first load is the heavy one (the day, in slices), and a deploy starts every instance at once: they begin
        // at different moments within half a load interval.
        jitter <- Random.nextLongBetween(0L, math.max(1L, settings.loadInterval.toMillis / 2))
        _ <- (ZIO.sleep(jitter.millis) *> (load(slice, first) *> ZIO.sleep(settings.loadInterval)).forever).forkScoped
        _ <- (ZIO.sleep(10.minutes) *> upkeep).forever.forkScoped
        // What is written last is written once more on the way out, so a deploy loses no more than the interval.
        _ <- ZIO.addFinalizer(flush.timeout(5.seconds).unit)
      yield ()
