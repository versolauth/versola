package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.{Duration, UIO, ZIO, durationInt}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
  */
final class SmsBudget(settings: SmsBudget.Settings):
  import SmsBudget.*

  private val windows = ConcurrentHashMap[Key, Window]()

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
    if !settings.enabled then Decision.Allowed
    else
      val k = key(tenantId, prefix)
      val existing = Option(windows.get(k))
      val window =
        if existing.isDefined || !charge then existing
        else if windows.size >= settings.maxRanges then None
        else Some(windows.computeIfAbsent(k, _ => Window()))
      window match
        case Some(w) => if w.admit(at, charge, closed(_, _, conversionBreaker)) then Decision.Allowed else Decision.Refused
        case None if charge => Decision.Untracked
        case None => Decision.Allowed

  /** Gives back a code that was charged at `at` and not sent after all. */
  def release(tenantId: Option[TenantId], prefix: String, at: Instant): Unit =
    Option(windows.get(key(tenantId, prefix))).foreach(_.giveBack(at))

  private def closed(sent: Int, verified: Int, conversionBreaker: Boolean): Boolean =
    (settings.sentPerHour > 0 && sent >= settings.sentPerHour) ||
      (conversionBreaker && settings.minVolume > 0 && sent >= settings.minVolume && verified < sent * settings.minConversion)

  /** Forgets ranges that have been quiet for the whole hour. */
  def evict(at: Instant): Unit =
    windows.entrySet().removeIf(entry => entry.getValue.totals(at) == (0, 0))

  def size: Int = windows.size

object SmsBudget:
  enum Decision:
    case Allowed, Refused, Untracked

  /** @param sentPerHour    codes allowed to one range of one tenant per hour; 0 means no limit
    * @param minVolume      codes sent before the conversion is looked at; 0 means it is not
    * @param minConversion  verified over sent below which a range with `minVolume` codes sent is closed
    * @param prefixLength   digits of the number, country code included, that make a range
    * @param maxRanges      ranges tracked at once; beyond that new ones are let through untracked
    */
  case class Settings(
      sentPerHour: Int,
      minVolume: Int,
      minConversion: Double,
      prefixLength: Int,
      maxRanges: Int,
  ):
    def enabled: Boolean = sentPerHour > 0 || minVolume > 0

  private case class Key(tenantId: String, prefix: String)

  private val Minutes = 60

  /** The last hour, a minute at a time. */
  private final class Window:
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
    def admit(at: Instant, charge: Boolean, closed: (Int, Int) => Boolean): Boolean = synchronized:
      val (sent, verified) = totals(at)
      val open = !closed(sent, verified)
      if open && charge then add(at, sent = 1, verified = 0)
      open

    def totals(at: Instant): (Int, Int) = synchronized:
      val now = at.getEpochSecond / 60
      (0 until Minutes).foldLeft((0, 0)):
        case ((s, v), slot) =>
          if now - minuteOf(slot) < Minutes && minuteOf(slot) <= now then (s + sent(slot), v + verified(slot)) else (s, v)
