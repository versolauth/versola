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
  *   - more than `sentPerHour` codes were sent to it, or
  *   - at least `minVolume` were sent and fewer than `minConversion` of them were verified.
  * It opens again by itself as the hour moves on. Both limits are off while they are 0.
  */
final class SmsBudget(settings: SmsBudget.Settings):
  import SmsBudget.*

  private val windows = ConcurrentHashMap[Key, Window]()

  private def key(tenantId: Option[TenantId], prefix: String): Key =
    Key(tenantId.map(identity[String]).getOrElse(""), prefix.take(settings.prefixLength))

  /** A code was handed to the provider for a number with this prefix. */
  def sent(tenantId: Option[TenantId], prefix: String, at: Instant): Unit =
    window(tenantId, prefix).foreach(_.add(at, sent = 1, verified = 0))

  /** A code sent to a number with this prefix was verified. */
  def verified(tenantId: Option[TenantId], prefix: String, at: Instant): Unit =
    window(tenantId, prefix).foreach(_.add(at, sent = 0, verified = 1))

  /** Whether a code may still be sent to a number with this prefix. A range the budget has no room to track is
    * allowed: the limit must not become the thing an attacker fills up. */
  def allows(tenantId: Option[TenantId], prefix: String, at: Instant): Boolean =
    if !settings.enabled then true
    else
      Option(windows.get(key(tenantId, prefix))).forall: window =>
        val (sent, verified) = window.totals(at)
        !(settings.sentPerHour > 0 && sent >= settings.sentPerHour) &&
        !(settings.minVolume > 0 && sent >= settings.minVolume && verified < sent * settings.minConversion)

  private def window(tenantId: Option[TenantId], prefix: String): Option[Window] =
    if !settings.enabled then None
    else
      val k = key(tenantId, prefix)
      Option(windows.get(k)).orElse:
        if windows.size >= settings.maxRanges then None
        else Some(windows.computeIfAbsent(k, _ => Window()))

  /** Forgets ranges that have been quiet for the whole hour. */
  def evict(at: Instant): Unit =
    windows.entrySet().removeIf(entry => entry.getValue.totals(at) == (0, 0))

  def size: Int = windows.size

object SmsBudget:
  /** @param sentPerHour    codes allowed to one range of one tenant per hour; 0 means no limit
    * @param minVolume      codes sent before the conversion is looked at; 0 means it is not
    * @param minConversion  verified over sent below which a range with `minVolume` codes sent is closed
    * @param prefixLength   digits of the number, country code included, that make a range
    * @param maxRanges      ranges tracked at once; beyond that new ones are let through untracked
    */
  case class Settings(
      sentPerHour: Int = 0,
      minVolume: Int = 0,
      minConversion: Double = 0.1,
      prefixLength: Int = 4,
      maxRanges: Int = 10000,
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

    def totals(at: Instant): (Int, Int) = synchronized:
      val now = at.getEpochSecond / 60
      (0 until Minutes).foldLeft((0, 0)):
        case ((s, v), slot) =>
          if now - minuteOf(slot) < Minutes && minuteOf(slot) <= now then (s + sent(slot), v + verified(slot)) else (s, v)

  val off: SmsBudget = SmsBudget(Settings())
