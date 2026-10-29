package versola.antifraud

import zio.*
import zio.json.JsonCodec

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.time.Instant

/** The velocity rules: a request fires one when its phone, address, device, ASN or range of numbers was seen more often
  * than allowed within a window.
  *
  * A rule's `test` is pure, so the counts are read before it runs and carried in the context's `signals`
  * ([[VelocityCounters.snapshot]]) under `velocity.<key>.<window>`. A count that is not there (counters off, key
  * unknown on this path) is 0, so the rule does not fire.
  *
  * Where the context's fields come from:
  *   - `phone`: `subjectHash`, the keyed hash of the number or address (never the number itself). It is the hash the
  *     event log uses, so it is **not scoped to a tenant**: one phone is one key across tenants, which lets abuse of one
  *     tenant weigh on the next, and means that a person's number is correlated across tenants (a privacy decision
  *     of the operator's; to scope it, key the hash per tenant). Rotating `security.antifraud-subject-secret` changes
  *     every phone key and so starts the history over,
  *   - `ip`: `ip`, which the caller reads from the request's log context (`Observability.currentIp`, the `auth.ip` of
  *     the `receive-http` line): the client's address only when the proxy in front sets the header and no client can
  *     choose its value. It is counted as its network, `/32` for IPv4 and `/64` for IPv6 (see [[network]]),
  *   - `device`, `asn`: `deviceId` and `asn` (both empty until P1-1 and P1-3 fill them),
  *   - `prefix`: the tenant and `destPrefix` (the leading digits of the number), so a tenant's ranges are its own.
  *
  * Mind what is counted. A count is of the steps the caller counts, and a rule on a phone that fires on the attempts
  * of somebody else is a way to lock its owner out. So the caller should [[VelocityCounters.snapshot]] first, decide,
  * and [[VelocityCounters.record]] only what it let through, and the `phone` limit should be read as a limit on codes
  * sent to that number. A user who is known (`device.known` or `session.recent_success` is `true` in the signals,
  * filled by later tasks) is let off part of the weight: see `trustedDiscount`. Several users share one IPv4 address
  * behind carrier-grade NAT, so the weights of `ip` and `asn` are low by default and `device` outweighs them.
  */
object VelocityRules:

  /** The most requests a key may have in each window; 0 means no limit. A count above the limit fires the rule.
    * @param weight what a fired rule of this key adds to the score */
  case class Limit(
      perMinute: Int,
      perTenMinutes: Int,
      perHour: Int,
      perDay: Int,
      weight: Int,
  ) derives JsonCodec:
    def of(window: VelocityWindow): Int = window match
      case VelocityWindow.OneMinute => perMinute
      case VelocityWindow.TenMinutes => perTenMinutes
      case VelocityWindow.OneHour => perHour
      case VelocityWindow.OneDay => perDay

  /** @param trustedDiscount taken off the score of a request of a known user that has fired a velocity rule (a rule
    *                        of its own, `velocity_known_user`); 0 for none
    */
  case class Thresholds(
      phone: Limit,
      ip: Limit,
      device: Limit,
      asn: Limit,
      prefix: Limit,
      trustedDiscount: Int,
  ) derives JsonCodec:
    def of(keyType: VelocityKey): Limit = keyType match
      case VelocityKey.phone => phone
      case VelocityKey.ip => ip
      case VelocityKey.device => device
      case VelocityKey.asn => asn
      case VelocityKey.prefix => prefix

  /** Signals that say the user is known: any of them `true`. */
  val trustedSignals: List[String] = List("device.known", "session.recent_success")

  private val labels = Map(
    VelocityWindow.OneMinute -> "1m",
    VelocityWindow.TenMinutes -> "10m",
    VelocityWindow.OneHour -> "1h",
    VelocityWindow.OneDay -> "24h",
  )

  def signal(keyType: VelocityKey, window: VelocityWindow): String = s"velocity.$keyType.${labels(window)}"

  def ruleName(keyType: VelocityKey, window: VelocityWindow): String = s"velocity_${keyType}_${labels(window)}"

  /** The network an address is counted as: `4:a.b.c.d` for an IPv4 address (an IPv4-mapped IPv6 one is that address),
    * `6:` and the first 64 bits in hex for IPv6, so that the spellings of one address (`::1`, `0:0::1`) are one key and a
    * client that rotates through its own /64 is one key too. `None` for anything but an address literal, a host name
    * included: nothing here reaches the resolver, and text that is not an address makes no key. */
  def network(text: String): Option[String] =
    val trimmed = text.trim
    val literal = trimmed.nonEmpty && trimmed.length <= 45 &&
      trimmed.forall(c => c.isDigit || c == '.' || c == ':' || (c.toLower >= 'a' && c.toLower <= 'f')) &&
      (trimmed.contains(':') || trimmed.count(_ == '.') == 3)
    if !literal then None
    else
      scala.util.Try(InetAddress.getByName(trimmed)).toOption.collect:
        case v4: Inet4Address => s"4:${v4.getHostAddress}"
        case v6: Inet6Address => "6:" + v6.getAddress.take(8).map("%02x".format(_)).mkString

  /** The keys the context can be told by, as counted; `ipNetwork` is the key of its address. */
  def keys(ctx: AntifraudContext, ipNetwork: Option[String]): List[(VelocityKey, String)] =
    List(
      ctx.subjectHash.map(VelocityKey.phone -> _),
      ipNetwork.map(VelocityKey.ip -> _),
      ctx.deviceId.map(VelocityKey.device -> _),
      ctx.asn.map(asn => VelocityKey.asn -> asn.toString),
      ctx.destPrefix.map(prefix => VelocityKey.prefix -> s"${ctx.tenantId.map(identity[String]).getOrElse("")}/$prefix"),
    ).flatten

  /** Reads the counts of the windows that have a limit, plus `extra` for the request that is not recorded yet, into
    * the context's `signals`. */
  def withSignals(
      counters: VelocityCounters,
      thresholds: Thresholds,
      ctx: AntifraudContext,
      keys: List[(VelocityKey, String)],
      at: Instant,
      extra: Int,
  ): AntifraudContext =
    val counts = keys.flatMap: (keyType, key) =>
      VelocityWindow.values.toList.collect:
        case window if thresholds.of(keyType).of(window) > 0 =>
          signal(keyType, window) -> (counters.count(keyType, key, window, at) + extra).toString
    ctx.copy(signals = ctx.signals ++ counts)

  /** The rules of the configured limits. Carry no state: the counts are in the context. */
  def rules(thresholds: Thresholds): List[AntifraudRule] =
    val limits =
      for
        keyType <- VelocityKey.values.toList
        limit = thresholds.of(keyType)
        window <- VelocityWindow.values.toList
        max = limit.of(window)
        if max > 0
      yield (
        signal(keyType, window),
        max,
        AntifraudRule(
          name = ruleName(keyType, window),
          weight = limit.weight,
          forces = None,
          test = _.signals.get(signal(keyType, window)).flatMap(_.toIntOption).exists(_ > max),
        ),
      )
    val known = AntifraudRule(
      name = "velocity_known_user",
      weight = -thresholds.trustedDiscount,
      forces = None,
      test = ctx =>
        trustedSignals.exists(ctx.signals.get(_).contains("true")) &&
          limits.exists((name, max, _) => ctx.signals.get(name).flatMap(_.toIntOption).exists(_ > max)),
    )
    limits.map(_._3) ++ Option.when(thresholds.trustedDiscount > 0 && limits.nonEmpty)(known)

  /** The rule set for [[Antifraud.Impl]], from the limits of the request's tenant (see [[AntifraudTuning]]); empty while the
    * counters are off. */
  def ruleSet(settings: VelocityCounters.Settings, tuning: AntifraudTuning): Antifraud.RuleSet =
    new Antifraud.RuleSet:
      override def rules: UIO[List[AntifraudRule]] = ZIO.succeed(if settings.enabled then VelocityRules.rules(tuning.current.thresholds) else Nil)

      override def rulesFor(ctx: AntifraudContext): UIO[List[AntifraudRule]] =
        ZIO.succeed(if settings.enabled then VelocityRules.rules(tuning.current.thresholds) else Nil)
