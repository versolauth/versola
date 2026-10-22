package versola.antifraud

import zio.*

import java.time.Instant

/** The velocity rules: a request fires one when its phone, address, device, ASN or range of numbers was seen more often
  * than allowed within a window.
  *
  * A rule's `test` is pure, so the counts are read before it runs and carried in the context's `signals`
  * ([[VelocityRules.snapshot]]) under `velocity.<key>.<window>`. The caller (the one that calls `Antifraud.evaluate`)
  * makes them with [[VelocityRules.recordAndSnapshot]] and passes the result on. A count that is not there (counters
  * off, key unknown on this path) is 0, so the rule does not fire.
  *
  * Where the context's fields come from:
  *   - `phone`: `subjectHash`, the keyed hash of the number or address (never the number itself),
  *   - `ip`: `ip`, which the caller takes from `ConversationRouter`'s `ipAddress` (`X-Forwarded-For`, then `X-Real-IP`):
  *     the address of the client only when the deployment's proxy sets those headers and is the only way in,
  *   - `device`: `deviceId`, `asn`: `asn` (both empty until P1-1 and P1-3 fill them),
  *   - `prefix`: the tenant and `destPrefix` (the leading digits of the number), so a tenant's ranges are its own.
  */
object VelocityRules:

  /** The most requests a key may have in each window; 0 means no limit. A count above the limit fires the rule.
    * @param weight what a fired rule of this key adds to the score */
  case class Limit(
      perMinute: Int = 0,
      perTenMinutes: Int = 0,
      perHour: Int = 0,
      perDay: Int = 0,
      weight: Int = 50,
  ):
    def of(window: VelocityWindow): Int = window match
      case VelocityWindow.OneMinute => perMinute
      case VelocityWindow.TenMinutes => perTenMinutes
      case VelocityWindow.OneHour => perHour
      case VelocityWindow.OneDay => perDay

  case class Thresholds(
      phone: Limit = Limit(),
      ip: Limit = Limit(),
      device: Limit = Limit(),
      asn: Limit = Limit(),
      prefix: Limit = Limit(),
  ):
    def of(keyType: VelocityKey): Limit = keyType match
      case VelocityKey.phone => phone
      case VelocityKey.ip => ip
      case VelocityKey.device => device
      case VelocityKey.asn => asn
      case VelocityKey.prefix => prefix

  private val labels = Map(
    VelocityWindow.OneMinute -> "1m",
    VelocityWindow.TenMinutes -> "10m",
    VelocityWindow.OneHour -> "1h",
    VelocityWindow.OneDay -> "24h",
  )

  def signal(keyType: VelocityKey, window: VelocityWindow): String = s"velocity.$keyType.${labels(window)}"

  def ruleName(keyType: VelocityKey, window: VelocityWindow): String = s"velocity_${keyType}_${labels(window)}"

  /** The keys the context can be told by, as counted. */
  private def keys(ctx: AntifraudContext): List[(VelocityKey, String)] =
    List(
      ctx.subjectHash.map(VelocityKey.phone -> _),
      ctx.ip.map(VelocityKey.ip -> _),
      ctx.deviceId.map(VelocityKey.device -> _),
      ctx.asn.map(asn => VelocityKey.asn -> asn.toString),
      ctx.destPrefix.map(prefix => VelocityKey.prefix -> s"${ctx.tenantId.map(identity[String]).getOrElse("")}/$prefix"),
    ).flatten

  /** Counts this request against each of its keys, then reads the counts (this request included, so a limit of N is
    * exceeded by the N+1st) of the windows that have a limit, into the context's `signals`. Returns the context
    * unchanged when the counters are off. Takes no effect that can fail or wait. */
  def recordAndSnapshot(counters: VelocityCounters, settings: VelocityCounters.Settings, ctx: AntifraudContext, at: Instant): AntifraudContext =
    if !settings.enabled then ctx
    else
      val counts = keys(ctx).flatMap: (keyType, key) =>
        counters.record(keyType, key, at)
        VelocityWindow.values.toList.collect:
          case window if settings.thresholds.of(keyType).of(window) > 0 =>
            signal(keyType, window) -> counters.count(keyType, key, window, at).toString
      ctx.copy(signals = ctx.signals ++ counts)

  /** The rules of the configured limits. Carry no state: the counts are in the context. */
  def rules(settings: VelocityCounters.Settings): List[AntifraudRule] =
    for
      keyType <- VelocityKey.values.toList
      limit = settings.thresholds.of(keyType)
      window <- VelocityWindow.values.toList
      max = limit.of(window)
      if max > 0
    yield
      val name = signal(keyType, window)
      AntifraudRule(
        name = ruleName(keyType, window),
        weight = limit.weight,
        forces = None,
        test = _.signals.get(name).flatMap(_.toIntOption).exists(_ > max),
      )

  /** The rule set for [[Antifraud.Impl]]; empty while the counters are off. */
  def ruleSet(settings: VelocityCounters.Settings): Antifraud.RuleSet =
    val all = if settings.enabled then rules(settings) else Nil
    new Antifraud.RuleSet:
      override def rules: UIO[List[AntifraudRule]] = ZIO.succeed(all)
