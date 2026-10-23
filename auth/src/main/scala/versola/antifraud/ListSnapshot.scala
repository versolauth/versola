package versola.antifraud

import versola.oauth.client.model.TenantId

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/** The outcome of looking a context up in the lists: the entry that decides (deny, fake or allow, the most specific,
  * and on a tie the harshest) and one watch entry, if any matched. */
case class ListMatch(decisive: Option[ListedValue], watch: Option[ListedValue], shadow: Option[ListedValue])

/** An address as two longs, the same for both families: an IPv4 address fills the high half. */
private[antifraud] case class IpKey(hi: Long, lo: Long)

private[antifraud] object IpKey:

  /** Keeps the leading `bits` bits (of 128). */
  def mask(key: IpKey, bits: Int): IpKey =
    def top(x: Long, n: Int): Long = if n <= 0 then 0L else if n >= 64 then x else x & (-1L << (64 - n))
    IpKey(top(key.hi, bits), top(key.lo, bits - 64))

/** An immutable index of the list entries, built off the request path and swapped in whole.
  *
  * A lookup is a few hash lookups: one per exact value, one per length of the destination prefix, one per distinct
  * prefix length of the networks present (so as many as the entries use, not as many as there are entries). It reads
  * nothing but the context and the clock, and allocates a little: it stays far under the 0.1 ms the request can
  * spend (see ListSnapshotSpec).
  */
final class ListSnapshot private (
    exact: Map[AntifraudValueType, Map[String, List[ListSnapshot.Indexed]]],
    prefixes: Map[String, List[ListSnapshot.Indexed]],
    asns: Map[Long, List[ListSnapshot.Indexed]],
    nets: Map[Boolean, ListSnapshot.Networks],
    val size: Int,
    val skipped: Int,
):
  import ListSnapshot.*

  def isEmpty: Boolean = size == 0

  def lookup(ctx: AntifraudContext, now: Instant): ListMatch =
    if isEmpty then ListMatch(None, None, None)
    else
      var decisive: Indexed | Null = null
      var watch: ListedValue | Null = null
      var shadow: ListedValue | Null = null

      def offer(found: List[Indexed]): Unit =
        var rest = found
        while rest.nonEmpty do
          val candidate = rest.head
          rest = rest.tail
          val entry = candidate.entry
          if entry.tenantId.forall(ctx.tenantId.contains) && entry.expiresAt.forall(_.isAfter(now)) then
            if entry.shadow then
              if shadow == null then shadow = entry
            else if entry.listType == AntifraudListType.watch then
              if watch == null then watch = entry
            else if decisive == null || candidate.outranks(decisive.nn) then decisive = candidate

      def exactly(valueType: AntifraudValueType, key: Option[String]): Unit =
        key.foreach: k =>
          exact.get(valueType).foreach(_.get(k).foreach(offer))

      exactly(AntifraudValueType.phone_hash, ctx.subjectHash)
      exactly(AntifraudValueType.device_id, ctx.deviceId)
      exactly(AntifraudValueType.ja4_hash, ctx.ja4)
      exactly(AntifraudValueType.email_domain, ctx.signals.get(AntifraudLists.EmailDomainSignal).map(_.trim.toLowerCase))
      if exact.contains(AntifraudValueType.ua_hash) then exactly(AntifraudValueType.ua_hash, ctx.userAgent.map(sha256Hex))
      ctx.destPrefix.foreach: dest =>
        if prefixes.nonEmpty then
          var length = math.min(dest.length, MaxPrefix)
          while length > 0 do
            prefixes.get(dest.substring(0, length)).foreach(offer)
            length -= 1
      ctx.asn.foreach: asn =>
        asns.get(asn.toLong & 0xffffffffL).foreach(offer)
      ctx.ip.flatMap(parseIp).foreach: (v6, key) =>
        nets.get(v6).foreach(_.lookup(key, offer))

      ListMatch(Option(decisive).map(_.nn.entry), Option(watch).map(_.nn), Option(shadow).map(_.nn))

object ListSnapshot:

  val empty: ListSnapshot = build(Nil)

  /** Longest prefix an entry may have: what [[AntifraudEvents]] keeps of a number. */
  private val MaxPrefix = AntifraudEvents.destPrefixLength

  /** An entry with how specific its value is.
    *
    * Specificity, from the most to the least: an exact address, destination, or device (1000), a network (500 plus
    * its prefix length), a JA4 fingerprint (450), a user agent (400), a number prefix (300 plus its length), an
    * autonomous system (200), a mail domain (100). A fingerprint and a user agent rank below any network: they are
    * easily copied and one value covers many real clients, so they must not outvote an address. Only entries of one request are compared, so the numbers say
    * "this value names fewer clients than that one", nothing more.
    */
  private[antifraud] case class Indexed(entry: ListedValue, specificity: Int):
    /** Whether this decides over `other`: more specific, or as specific and harsher (deny, then fake, then allow). */
    def outranks(other: Indexed): Boolean =
      specificity > other.specificity ||
        (specificity == other.specificity && harshness(entry.listType) > harshness(other.entry.listType))

  private def harshness(listType: AntifraudListType): Int = listType match
    case AntifraudListType.deny => 3
    case AntifraudListType.fake => 2
    case AntifraudListType.allow => 1
    case AntifraudListType.watch => 0

  /** Networks of one family, by prefix length, longest first. */
  private[antifraud] final class Networks(byLength: Array[(Int, Map[IpKey, List[Indexed]])]):
    def lookup(key: IpKey, offer: List[Indexed] => Unit): Unit =
      var i = 0
      while i < byLength.length do
        val (bits, entries) = byLength(i)
        entries.get(IpKey.mask(key, bits)).foreach(offer)
        i += 1

  /** The entries that cannot be read (a value written by hand that is not valid for its type) are left out and
    * counted in [[ListSnapshot.skipped]]: one bad row must not take the lists down. */
  def build(values: Iterable[ListedValue]): ListSnapshot =
    val exactIndex = mutable.Map.empty[AntifraudValueType, mutable.Map[String, List[Indexed]]]
    val prefixIndex = mutable.Map.empty[String, List[Indexed]]
    val asnIndex = mutable.Map.empty[Long, List[Indexed]]
    // family (true is IPv6) -> prefix length (of 128) -> network
    val netIndex = mutable.Map.empty[Boolean, mutable.Map[Int, mutable.Map[IpKey, List[Indexed]]]]
    var size = 0
    var skipped = 0

    def add[K](index: mutable.Map[K, List[Indexed]], key: K, indexed: Indexed): Unit =
      index.update(key, indexed :: index.getOrElse(key, Nil))

    values.foreach: v =>
      ListValues.normalize(v.valueType, v.value) match
        case Left(_) => skipped += 1
        case Right(value) =>
          size += 1
          v.valueType match
            case AntifraudValueType.prefix => add(prefixIndex, value, Indexed(v, 300 + value.length))
            case AntifraudValueType.asn => add(asnIndex, value.toLong, Indexed(v, 200))
            case AntifraudValueType.ip =>
              val (v6, key) = parseIp(value).get
              val bits = if v6 then 128 else 32
              val level = netIndex.getOrElseUpdate(v6, mutable.Map.empty).getOrElseUpdate(bits, mutable.Map.empty)
              add(level, key, Indexed(v, 1000))
            case AntifraudValueType.cidr =>
              val Array(address, length) = value.split('/')
              val (v6, key) = parseIp(address).get
              val bits = length.toInt
              val level = netIndex.getOrElseUpdate(v6, mutable.Map.empty).getOrElseUpdate(bits, mutable.Map.empty)
              add(level, key, Indexed(v, 500 + bits))
            case other =>
              val specificity = other match
                case AntifraudValueType.ja4_hash => 450
                case AntifraudValueType.ua_hash => 400
                case AntifraudValueType.email_domain => 100
                case _ => 1000
              add(exactIndex.getOrElseUpdate(other, mutable.Map.empty), value, Indexed(v, specificity))

    ListSnapshot(
      exact = exactIndex.view.mapValues(_.toMap).toMap,
      prefixes = prefixIndex.toMap,
      asns = asnIndex.toMap,
      nets = netIndex.view.mapValues: levels =>
        // The network of an IPv4 address is kept in the high half, so its length counts from there.
        Networks(levels.toArray.sortBy(-_._1).map((bits, entries) => (bits, entries.toMap)))
      .toMap,
      size = size,
      skipped = skipped,
    )

  def sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

  /** `Some((isIpv6, key))` for an address literal, `None` for anything else, a host name included: nothing here may
    * reach the resolver. An IPv4-mapped IPv6 address is the IPv4 address. */
  private[antifraud] def parseIp(text: String): Option[(Boolean, IpKey)] =
    val trimmed = text.trim
    val literal =
      trimmed.nonEmpty && trimmed.length <= 45 && trimmed.forall(c => c.isDigit || c == '.' || c == ':' || (c.toLower >= 'a' && c.toLower <= 'f'))
    if !literal || !(trimmed.contains(':') || trimmed.count(_ == '.') == 3) then None
    else
      scala.util.Try(InetAddress.getByName(trimmed).getAddress).toOption.collect:
        case Array(a, b, c, d) =>
          val v4 = ((a & 0xffL) << 24) | ((b & 0xffL) << 16) | ((c & 0xffL) << 8) | (d & 0xffL)
          (false, IpKey(v4 << 32, 0L))
        case bytes if bytes.length == 16 =>
          def word(from: Int) = bytes.slice(from, from + 8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
          (true, IpKey(word(0), word(8)))

/** Checks and canonicalises the value of an entry, so that what is stored is what a lookup compares. */
object ListValues:

  private val hex64 = "[0-9a-f]{64}".r
  private val domain = "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*".r

  def normalize(valueType: AntifraudValueType, raw: String): Either[String, String] =
    val value = raw.trim
    if value.isEmpty then Left("the value is empty")
    else if value.length > 256 then Left("the value is longer than 256 characters")
    else
      valueType match
        case AntifraudValueType.phone_hash | AntifraudValueType.device_id | AntifraudValueType.ja4_hash => Right(value)
        case AntifraudValueType.ua_hash =>
          Option.when(hex64.matches(value.toLowerCase))(value.toLowerCase).toRight("a ua_hash is 64 hex digits (SHA-256)")
        case AntifraudValueType.prefix =>
          Option.when(value.forall(_.isDigit) && value.length <= AntifraudEvents.destPrefixLength)(value)
            .toRight(s"a prefix is 1 to ${AntifraudEvents.destPrefixLength} digits, without the +")
        case AntifraudValueType.asn =>
          val digits = value.toUpperCase.stripPrefix("AS")
          Option.when(digits.nonEmpty && digits.length <= 10 && digits.forall(_.isDigit) && digits.toLong <= 4294967295L)(digits.toLong.toString)
            .toRight("an asn is a number up to 4294967295")
        case AntifraudValueType.email_domain =>
          Option.when(domain.matches(value.toLowerCase))(value.toLowerCase).toRight("not a domain name")
        case AntifraudValueType.ip =>
          ListSnapshot.parseIp(value).map(canonical(_, None)).toRight("not an IP address")
        case AntifraudValueType.cidr =>
          value.split('/') match
            case Array(address, length) =>
              for
                parsed <- ListSnapshot.parseIp(address).toRight("not an IP address before the /")
                bits <- length.toIntOption.filter(n => n >= 0 && n <= (if parsed._1 then 128 else 32)).toRight("the prefix length is out of range")
              yield canonical(parsed, Some(bits))
            case _ => Left("a network is address/length")

  /** `allow` is for exact subjects only: an allow on a network, an ASN, a prefix, a mail domain, a fingerprint or a
    * user agent would exempt a whole population from the score. A network is exact at /32 or /128. */
  def allowable(valueType: AntifraudValueType, normalized: String): Boolean = valueType match
    case AntifraudValueType.phone_hash | AntifraudValueType.device_id | AntifraudValueType.ip => true
    case AntifraudValueType.cidr => normalized.endsWith("/32") || normalized.endsWith("/128")
    case _ => false

  /** Entries that name many clients (anything but an exact subject, and a network wider than /24 or /48) must
    * expire, so that a wrong one does not outlive the attack it was made for. */
  def broad(valueType: AntifraudValueType, normalized: String): Boolean = valueType match
    case AntifraudValueType.phone_hash | AntifraudValueType.device_id | AntifraudValueType.ip => false
    case AntifraudValueType.cidr =>
      val bits = normalized.substring(normalized.indexOf('/') + 1).toInt
      bits < (if normalized.contains(':') then 48 else 24)
    case _ => true

  /** The address with the bits beyond the prefix cleared, as text. */
  private def canonical(parsed: (Boolean, IpKey), bits: Option[Int]): String =
    val (v6, key) = parsed
    val masked = bits.map(b => IpKey.mask(key, b)).getOrElse(key)
    val address =
      if v6 then
        (0 until 8).map: i =>
          val word = if i < 4 then masked.hi else masked.lo
          ((word >>> (16 * (3 - i % 4))) & 0xffff).toHexString
        .mkString(":")
      else (0 until 4).map(i => ((masked.hi >>> (56 - 8 * i)) & 0xff).toString).mkString(".")
    bits.fold(address)(b => s"$address/$b")
