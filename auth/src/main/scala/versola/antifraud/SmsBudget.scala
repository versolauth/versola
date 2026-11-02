package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.{Duration, UIO, ZIO, durationInt}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A limit on how many codes may be sent to one range of phone numbers within an hour, and a breaker for a range
  * where the codes are not being used.
  *
  * SMS pumping looks like many requests to numbers of one range, almost none of which are ever verified. Both
  * signs are kept per tenant and per range (the leading digits of the number, `prefixLength` of them) over the
  * last hour, in memory and per instance: no database is read on the request, and what one instance misses of the
  * traffic another instance sees, so the limit is approximate by the number of instances. A range is closed when
  *   - `sentPerHour` codes were sent to it already (so that many are allowed), or
  *   - at least `minVolume` were sent and fewer than `minConversion` of them were verified.
  * It opens again by itself as the hour moves on. Both limits are off while they are 0.
  *
  * The table is bounded by `maxRanges`. Ranges are made by whoever asks (the leading digits of a number), so a full table
  * does not stop new ranges from being tracked: the range with the fewest codes sent in the last ten minutes, of eight
  * picked at random, makes room (see [[VelocityCounters]], which does the same with its keys). A busy range, the kind an
  * attack makes, is the last to be evicted, and one a flood of made-up ranges pushes out loses its hour of counts and
  * starts again, which is the budget failing open for that range, not for the others.
  */
final class SmsBudget(settings: SmsBudget.Settings, sizing: SmsBudget.Sizing, limits: () => SmsLimits):
  import SmsBudget.*

  private val windows = ConcurrentHashMap[Key, Window]()
  // The ranges by slot, to pick some at random when the table is full; a little room beyond `maxRanges` for a burst that overshoots.
  private val slots = SampledSlots[Window](sizing.maxRanges + SlotSlack)
  private val evictedCount = AtomicLong(0)

  private def key(tenantId: Option[TenantId], prefix: String): Key =
    Key(tenantId.map(identity[String]).getOrElse(""), prefix.take(settings.prefixLength))

  /** A code sent to a number with this prefix was verified. */
  def verified(tenantId: Option[TenantId], prefix: String, at: Instant): Unit =
    Option(windows.get(key(tenantId, prefix))).foreach(_.add(at, sent = 0, verified = 1))

  /** Whether a code may be sent to a number with this prefix. With `charge` the code is counted in the same step
    * as it is allowed, so that requests arriving at once cannot all pass before the first is counted; without it
    * only the answer is given, for a request that sends nothing but must be answered exactly as one that does. A
    * range the budget has no room to track is let through ([[Decision.Untracked]]): the limit must not become the
    * thing an attacker fills up. */
  def decide(tenantId: Option[TenantId], prefix: String, at: Instant, charge: Boolean, conversionBreaker: Boolean = true): Decision =
    assess(tenantId, prefix, at, charge, conversionBreaker, enforce = true).decision

  /** [[decide]], and why a range is closed. Without `enforce` (the `shadow` mode of the tuning) a closed range is
    * answered [[Decision.WouldRefuse]] and the code is counted all the same, since it is going to be sent: the window
    * then holds what really went out, which is what the limit would have been applied to. */
  def assess(
      tenantId: Option[TenantId],
      prefix: String,
      at: Instant,
      charge: Boolean,
      conversionBreaker: Boolean,
      enforce: Boolean,
  ): Verdict =
    val limit = limits().of(prefix)
    if !limit.enabled then Verdict(Decision.Allowed, None)
    else
      val k = key(tenantId, prefix)
      val existing = Option(windows.get(k))
      val window =
        if existing.isDefined || !charge then existing
        else admit(k, at)
      window match
        case Some(w) =>
          w.admit(at, charge, closed(_, _, conversionBreaker, limit), enforce) match
            case None => Verdict(Decision.Allowed, None)
            case reason => Verdict(if enforce then Decision.Refused else Decision.WouldRefuse, reason)
        case None if charge => Verdict(Decision.Untracked, None)
        case None => Verdict(Decision.Allowed, None)

  private def admit(k: Key, at: Instant): Option[Window] =
    if windows.size >= sizing.maxRanges && !makeRoom(at) then None
    else
      val window = Window(k)
      val slot = slots.claim(window)
      if slot < 0 then None
      else
        window.slot = slot
        val raced = windows.putIfAbsent(k, window)
        if raced == null then Some(window)
        else
          // Another request made the range first: use its window, and give the slot back.
          slots.release(slot, window)
          Some(raced)

  private def makeRoom(at: Instant): Boolean =
    val sample = slots.sample(EvictionSample)
    if sample.isEmpty then false
    else
      val victim = sample.minBy(_.sentSince(at, RecentMinutes))
      if windows.remove(victim.key, victim) then
        slots.release(victim.slot, victim)
        evictedCount.incrementAndGet()
        true
      else false

  /** Ranges evicted to make room since the last call (for the metric). */
  def drainEvicted(): Long = evictedCount.getAndSet(0)

  /** Gives back a code that was charged at `at` and not sent after all. */
  def release(tenantId: Option[TenantId], prefix: String, at: Instant): Unit =
    Option(windows.get(key(tenantId, prefix))).foreach(_.giveBack(at))

  private def closed(sent: Int, verified: Int, conversionBreaker: Boolean, limit: SmsLimit): Option[Reason] =
    if limit.sentPerHour > 0 && sent >= limit.sentPerHour then Some(Reason.Cap)
    else if conversionBreaker && limit.minVolume > 0 && sent >= limit.minVolume && verified < sent * limit.minConversion then Some(Reason.Conversion)
    else None

  /** Forgets ranges that have been quiet for the whole hour. */
  def evict(at: Instant): Unit =
    windows.entrySet().removeIf: entry =>
      val gone = entry.getValue.totals(at) == (0, 0)
      if gone then slots.release(entry.getValue.slot, entry.getValue)
      gone

  /** Forgets the ranges that `prefix` (digits) is in or contains, of every tenant; every range for `None`. A range's budget
    * starts over: its count and the conversion that goes with it (a reset: see [[AntifraudCounters]]). */
  def forget(prefix: Option[String]): Unit =
    windows.entrySet().removeIf: entry =>
      val range = entry.getKey.prefix
      val matches = prefix.forall(p => range.startsWith(p) || p.startsWith(range))
      if matches then slots.release(entry.getValue.slot, entry.getValue)
      matches

  def size: Int = windows.size

object SmsBudget:
  /** A budget whose limits are `limits` everywhere, for where nothing is tuned. */
  def apply(settings: Settings, sizing: Sizing, limits: SmsLimits): SmsBudget = new SmsBudget(settings, sizing, () => limits)

  enum Decision:
    case Allowed, Refused, Untracked

    /** The range is closed, but the budget is not being enforced (`shadow`): the code goes out. */
    case WouldRefuse

  /** Why a range is closed: `sentPerHour` codes went out already, or too few of what went out was verified. */
  enum Reason(val label: String):
    case Cap extends Reason("cap")
    case Conversion extends Reason("conversion")

  case class Verdict(decision: Decision, reason: Option[Reason])

  /** What a deployment sets (`antifraud.sms-budget` of the config); the limits themselves are tuned (see [[AntifraudTuning]]).
    *
    * @param prefixLength digits of the number, country code included, that make a range; changing it starts the counts over
    */
  case class Settings(prefixLength: Int)

  /** What is not configuration.
    *
    * @param maxRanges ranges tracked at once; beyond that new ones are let through untracked
    */
  case class Sizing(maxRanges: Int)

  object Sizing:
    val standard: Sizing = Sizing(maxRanges = 10000)

  private case class Key(tenantId: String, prefix: String)

  private val Minutes = 60

  /** Ranges looked at when a full table makes room for a new one, and the minutes their use is counted over. */
  private val EvictionSample = 8
  private val RecentMinutes = 10
  private val SlotSlack = 64

  /** The last hour, a minute at a time. */
  private final class Window(val key: Key):
    @volatile var slot: Int = -1

    private val sent = new Array[Int](Minutes)
    private val verified = new Array[Int](Minutes)
    private val minuteOf = new Array[Long](Minutes)

    def add(at: Instant, sent: Int, verified: Int): Unit = synchronized:
      val minute = at.getEpochSecond / 60
      val slot = (minute % Minutes).toInt
      if minuteOf(slot) != minute then
        minuteOf(slot) = minute
        this.sent(slot) = 0
        this.verified(slot) = 0
      this.sent(slot) += sent
      this.verified(slot) += verified

    /** Takes back a charge of the minute of `at`; a minute that has since been reused is left alone. */
    def giveBack(at: Instant): Unit = synchronized:
      val minute = at.getEpochSecond / 60
      val slot = (minute % Minutes).toInt
      if minuteOf(slot) == minute && sent(slot) > 0 then sent(slot) -= 1

    /** Checks and charges in one step. */
    def admit(at: Instant, charge: Boolean, closed: (Int, Int) => Option[Reason], enforce: Boolean): Option[Reason] = synchronized:
      val (sent, verified) = totals(at)
      val why = closed(sent, verified)
      // A closed range that is not being enforced sends the code, so the code is counted.
      if (why.isEmpty || !enforce) && charge then add(at, sent = 1, verified = 0)
      why

    /** Codes sent in the last `minutes` minutes. */
    def sentSince(at: Instant, minutes: Int): Int = synchronized:
      val now = at.getEpochSecond / 60
      (0 until Minutes).foldLeft(0): (sum, slot) =>
        if now - minuteOf(slot) < minutes && minuteOf(slot) <= now then sum + sent(slot) else sum

    def totals(at: Instant): (Int, Int) = synchronized:
      val now = at.getEpochSecond / 60
      (0 until Minutes).foldLeft((0, 0)):
        case ((s, v), slot) =>
          if now - minuteOf(slot) < Minutes && minuteOf(slot) <= now then (s + sent(slot), v + verified(slot)) else (s, v)
