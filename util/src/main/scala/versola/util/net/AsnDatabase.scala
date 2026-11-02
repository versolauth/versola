package versola.util.net

import java.io.{BufferedInputStream, IOException, InputStream}
import java.lang.Long as JLong
import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Arrays
import java.util.zip.GZIPInputStream
import scala.collection.mutable
import scala.collection.mutable.ArrayBuilder

/** The autonomous system that announces an address.
  *
  * @param asn     the AS number; the database holds the ones that fit an `Int` (every AS number in use does: the 32-bit
  *                numbers above 2^31 are private use and reserved)
  * @param country the ISO 3166 code of the country the AS is registered in, `ZZ` where the source gives none
  * @param name    the AS description of the source (the registry's name of the holder, with the AS handle)
  */
case class AsnInfo(asn: Int, country: String, name: String)

/** A table of the address ranges the autonomous systems announce, to look an address up in: immutable, built off the
  * request path (see [[AsnDatabaseLoader]]) and swapped in whole.
  *
  * The ranges are held as sorted arrays of primitives, an IPv4 address as an unsigned 32-bit value in a `Long`, an IPv6
  * address as two `Long`s, and a lookup is one binary search: no allocation but the answer, and a microsecond or less. The
  * AS descriptions are shared between the ranges of one AS, so half a million ranges take a few tens of megabytes.
  */
final class AsnDatabase private (
    starts4: Array[Long],
    ends4: Array[Long],
    info4: Array[Int],
    starts6Hi: Array[Long],
    starts6Lo: Array[Long],
    ends6Hi: Array[Long],
    ends6Lo: Array[Long],
    info6: Array[Int],
    infos: Array[AsnInfo],
):
  import AsnDatabase.*

  /** The number of ranges, both families. */
  def ranges: Int = starts4.length + starts6Hi.length

  /** The AS of an address literal (see [[IpLiteral]]); `None` for text that is not one, and for an address no AS announces. */
  def lookup(text: String): Option[AsnInfo] =
    IpLiteral.parse(text).flatMap((v6, key) => lookup(v6, key))

  def lookup(address: InetAddress): Option[AsnInfo] =
    address match
      case v4: Inet4Address => lookup(false, IpKey(hi = ipv4(v4.getAddress) << 32, lo = 0L))
      case v6: Inet6Address =>
        val bytes = v6.getAddress
        lookup(true, IpKey(hi = word(bytes, 0), lo = word(bytes, 8)))
      case _ => None

  /** `v6` is the family, `key` the address as [[IpLiteral]] reads it. */
  def lookup(v6: Boolean, key: IpKey): Option[AsnInfo] =
    if !v6 then
      val ip = key.hi >>> 32
      val i = floor4(ip)
      if i >= 0 && ip <= ends4(i) then Some(infos(info4(i))) else None
    else
      val i = floor6(key.hi, key.lo)
      if i >= 0 && compare(key.hi, key.lo, ends6Hi(i), ends6Lo(i)) <= 0 then Some(infos(info6(i))) else None

  /** The index of the last range that starts at or before `ip`, or -1. */
  private def floor4(ip: Long): Int =
    var lo = 0
    var hi = starts4.length - 1
    var found = -1
    while lo <= hi do
      val mid = (lo + hi) >>> 1
      if starts4(mid) <= ip then
        found = mid
        lo = mid + 1
      else hi = mid - 1
    found

  private def floor6(hiBits: Long, loBits: Long): Int =
    var lo = 0
    var hi = starts6Hi.length - 1
    var found = -1
    while lo <= hi do
      val mid = (lo + hi) >>> 1
      if compare(starts6Hi(mid), starts6Lo(mid), hiBits, loBits) <= 0 then
        found = mid
        lo = mid + 1
      else hi = mid - 1
    found

object AsnDatabase:

  /** A database read from a source, and what was left out of it.
    *
    * @param malformed    lines that are not a range (too few columns, an address that is not a literal, a range that ends
    *                     before it starts or spans both families, an AS number that is not one, a line over [[MaxLine]]
    *                     bytes, or a range that overlaps an earlier one): left out and counted, never a failure
    * @param notAnnounced ranges of AS 0, which the source lists for the space no AS announces: no information, so not held
    */
  case class Loaded(database: AsnDatabase, malformed: Int, notAnnounced: Int)

  val empty: AsnDatabase =
    AsnDatabase(
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyIntArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyIntArray,
      Array.empty,
    )

  /** The longest line read, in bytes: a real row is under 300. A file with a longer one is not this format, and a line
    * with no end must not be held in memory. */
  val MaxLine = 4096

  /** The most that is read of a file once it is unpacked, so that a small file that unpacks into gigabytes is not read to the end. */
  val MaxUnpackedBytes: Long = 1L << 30

  /** The most ranges a database holds, and so the memory a file can ask for: the real table has about 500 thousand, so a file
    * with many times as many is not that table. Reading it fails rather than stops short, since half a table is a wrong one. */
  val MaxRanges: Int = 4000000

  /** Reads a database from a file of the `ip2asn-combined.tsv` format of iptoasn.com, gzipped or plain (told by the first two
    * bytes, not by the name): one range per line, `range_start`, `range_end`, `AS_number`, `country_code` and `AS_description`
    * separated by tabs, the two families together. Blank lines and lines that begin with `#` are skipped. The rows need not
    * be sorted; a row that overlaps an earlier one is dropped. Blocking: it reads the whole file. */
  def read(path: Path): Loaded =
    val in = Files.newInputStream(path)
    try read(in)
    finally in.close()

  def read(source: InputStream, maxRanges: Int = MaxRanges, maxUnpacked: Long = MaxUnpackedBytes): Loaded =
    val buffered = BufferedInputStream(source, 1 << 16)
    buffered.mark(2)
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()
    val gzipped = first == 0x1f && second == 0x8b
    val unpacked: InputStream = if gzipped then GZIPInputStream(buffered, 1 << 16) else buffered
    parse(Bounded(unpacked, maxUnpacked), maxRanges)

  private def parse(in: InputStream, maxRanges: Int): Loaded =
    val builder = Builder(maxRanges)
    forEachLine(in)(builder.add, builder.reject)
    builder.build()

  private class Builder(maxRanges: Int):
    private val starts4 = ArrayBuilder.ofLong()
    private val ends4 = ArrayBuilder.ofLong()
    private val info4 = ArrayBuilder.ofInt()
    private val starts6Hi = ArrayBuilder.ofLong()
    private val starts6Lo = ArrayBuilder.ofLong()
    private val ends6Hi = ArrayBuilder.ofLong()
    private val ends6Lo = ArrayBuilder.ofLong()
    private val info6 = ArrayBuilder.ofInt()
    private val infos = mutable.ArrayBuffer.empty[AsnInfo]
    // The description of an AS is shared by its ranges: one object per AS (and per spelling, if the source has two).
    private val byAsn = mutable.HashMap.empty[Int, Int]
    private var malformed = 0
    private var notAnnounced = 0
    private var accepted = 0

    def reject(): Unit = malformed += 1

    def add(line: String): Unit =
      if line.isEmpty || line.charAt(0) == '#' || line.isBlank then ()
      else
        val t1 = line.indexOf('\t')
        val t2 = if t1 < 0 then -1 else line.indexOf('\t', t1 + 1)
        val t3 = if t2 < 0 then -1 else line.indexOf('\t', t2 + 1)
        if t3 < 0 then malformed += 1
        else
          val t4 = line.indexOf('\t', t3 + 1)
          val asn = parseAsn(line, t2 + 1, t3)
          val start = parseAddress(line.substring(0, t1))
          val end = parseAddress(line.substring(t1 + 1, t2))
          if asn < 0 || start.isEmpty || end.isEmpty || start.get._1 != end.get._1 || compare(start.get._2, end.get._2) > 0 then malformed += 1
          else if asn == 0 then notAnnounced += 1
          else
            val country = normalizeCountry(line.substring(t3 + 1, if t4 < 0 then line.length else t4))
            val name = if t4 < 0 then "" else line.substring(t4 + 1).trim
            accepted += 1
            if accepted > maxRanges then throw IOException(s"more than $maxRanges ranges")
            val index = infoIndex(asn.toInt, country, name)
            val (v6, from) = start.get
            val to = end.get._2
            if v6 then
              starts6Hi += from.hi
              starts6Lo += from.lo
              ends6Hi += to.hi
              ends6Lo += to.lo
              info6 += index
            else
              starts4 += from.hi >>> 32
              ends4 += to.hi >>> 32
              info4 += index

    private def infoIndex(asn: Int, country: String, name: String): Int =
      byAsn.get(asn) match
        case Some(i) if infos(i).country == country && infos(i).name == name => i
        case _ =>
          infos += AsnInfo(asn = asn, country = country, name = name)
          byAsn.update(asn, infos.length - 1)
          infos.length - 1

    def build(): Loaded =
      val s4 = starts4.result()
      val e4 = ends4.result()
      val i4 = info4.result()
      val (os4, oe4, oi4, dropped4) = order4(s4, e4, i4)
      val s6h = starts6Hi.result()
      val s6l = starts6Lo.result()
      val e6h = ends6Hi.result()
      val e6l = ends6Lo.result()
      val i6 = info6.result()
      val (os6h, os6l, oe6h, oe6l, oi6, dropped6) = order6(s6h, s6l, e6h, e6l, i6)
      Loaded(
        database = AsnDatabase(os4, oe4, oi4, os6h, os6l, oe6h, oe6l, oi6, infos.toArray),
        malformed = malformed + dropped4 + dropped6,
        notAnnounced = notAnnounced,
      )

  private def parseAddress(text: String): Option[(Boolean, IpKey)] = IpLiteral.parse(text)

  /** An AS number from `line(from until to)` that fits an `Int`, or -1. */
  private def parseAsn(line: String, from: Int, until: Int): Long =
    if until - from < 1 || until - from > 10 then -1L
    else
      var value = 0L
      var i = from
      while i < until && value >= 0 do
        val c = line.charAt(i)
        value = if c >= '0' && c <= '9' then value * 10 + (c - '0') else -1L
        i += 1
      if value > Int.MaxValue then -1L else value

  private def normalizeCountry(raw: String): String =
    val code = raw.trim
    if code.length == 2 && code.forall(c => (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) then code.toUpperCase else "ZZ"

  private def compare(a: IpKey, b: IpKey): Int = compare(a.hi, a.lo, b.hi, b.lo)

  /** The order of two IPv6 addresses (unsigned, the high half first). */
  private[net] def compare(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int =
    val high = JLong.compareUnsigned(aHi, bHi)
    if high != 0 then high else JLong.compareUnsigned(aLo, bLo)

  /** The ranges in order, each starting after the one before ends: a file in order is checked in one pass, one that is not is
    * sorted, and a range that begins inside the one before is dropped (and counted). */
  private def order4(starts: Array[Long], ends: Array[Long], infos: Array[Int]): (Array[Long], Array[Long], Array[Int], Int) =
    val n = starts.length
    var ordered = true
    var i = 1
    while ordered && i < n do
      ordered = starts(i) > ends(i - 1)
      i += 1
    if ordered then (starts, ends, infos, 0)
    else
      val sorted = (0 until n).toArray.sortBy(starts(_))
      val keep = ArrayBuilder.ofInt()
      var lastEnd = -1L
      sorted.foreach: row =>
        if starts(row) > lastEnd then
          keep += row
          lastEnd = ends(row)
      val kept = keep.result()
      (kept.map(starts), kept.map(ends), kept.map(infos), n - kept.length)

  private def order6(
      sHi: Array[Long],
      sLo: Array[Long],
      eHi: Array[Long],
      eLo: Array[Long],
      infos: Array[Int],
  ): (Array[Long], Array[Long], Array[Long], Array[Long], Array[Int], Int) =
    val n = sHi.length
    var ordered = true
    var i = 1
    while ordered && i < n do
      ordered = compare(sHi(i), sLo(i), eHi(i - 1), eLo(i - 1)) > 0
      i += 1
    if ordered then (sHi, sLo, eHi, eLo, infos, 0)
    else
      val sorted = (0 until n).toArray.sortWith((a, b) => compare(sHi(a), sLo(a), sHi(b), sLo(b)) < 0)
      val keep = ArrayBuilder.ofInt()
      var lastHi = 0L
      var lastLo = 0L
      var first = true
      sorted.foreach: row =>
        if first || compare(sHi(row), sLo(row), lastHi, lastLo) > 0 then
          keep += row
          first = false
          lastHi = eHi(row)
          lastLo = eLo(row)
      val kept = keep.result()
      (kept.map(sHi), kept.map(sLo), kept.map(eHi), kept.map(eLo), kept.map(infos), n - kept.length)

  /** Calls `onLine` with each line (without its end), and `onTooLong` for each one longer than [[MaxLine]], which is not
    * held. The bytes are scanned where they are read, so that a file with no line ends cannot fill the memory the way
    * `BufferedReader.readLine` would let it. */
  private def forEachLine(in: InputStream)(onLine: String => Unit, onTooLong: () => Unit): Unit =
    val buffer = new Array[Byte](1 << 16)
    var line = new Array[Byte](512)
    var length = 0
    var tooLong = false

    def finish(): Unit =
      if tooLong then onTooLong()
      else
        val end = if length > 0 && line(length - 1) == '\r'.toByte then length - 1 else length
        onLine(String(line, 0, end, StandardCharsets.UTF_8))
      length = 0
      tooLong = false

    var read = in.read(buffer)
    while read >= 0 do
      var i = 0
      while i < read do
        val b = buffer(i)
        if b == '\n'.toByte then finish()
        else if !tooLong then
          if length == MaxLine then tooLong = true
          else
            if length == line.length then line = Arrays.copyOf(line, math.min(line.length * 2, MaxLine))
            line(length) = b
            length += 1
        i += 1
      read = in.read(buffer)
    if length > 0 || tooLong then finish()

  /** Stops with an `IOException` after `limit` bytes. */
  private class Bounded(in: InputStream, limit: Long) extends InputStream:
    private var taken = 0L

    override def read(): Int =
      val b = in.read()
      if b >= 0 then count(1)
      b

    override def read(into: Array[Byte], off: Int, len: Int): Int =
      val n = in.read(into, off, len)
      if n > 0 then count(n)
      n

    private def count(n: Int): Unit =
      taken += n
      if taken > limit then throw IOException(s"more than $limit bytes once unpacked")

  private def ipv4(bytes: Array[Byte]): Long =
    ((bytes(0) & 0xffL) << 24) | ((bytes(1) & 0xffL) << 16) | ((bytes(2) & 0xffL) << 8) | (bytes(3) & 0xffL)

  private def word(bytes: Array[Byte], from: Int): Long =
    bytes.slice(from, from + 8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
