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

/** A table of address ranges, each with a value, to look an address up in: immutable, built off the request path (see
  * [[RangeDatabaseLoader]]) and swapped in whole. What a range stands for, and how a file spells it, is the [[RangeFormat]]:
  * the autonomous system an address is announced by ([[AsnDatabase]]), the country it is in ([[DbIpCountryFormat]]).
  *
  * The ranges are held as sorted arrays of primitives, an IPv4 address as an unsigned 32-bit value in a `Long`, an IPv6
  * address as two `Long`s, each with the index of its value in a table of the distinct values, and a lookup is one binary
  * search: no allocation but the answer, and a microsecond or less. A value is held once however many ranges have it, so
  * a range costs its two bounds and four bytes of index, and a table of a few million ranges takes tens of megabytes.
  *
  * @tparam A the value of a range
  */
final class RangeDatabase[A] private (
    starts4: Array[Long],
    ends4: Array[Long],
    index4: Array[Int],
    starts6Hi: Array[Long],
    starts6Lo: Array[Long],
    ends6Hi: Array[Long],
    ends6Lo: Array[Long],
    index6: Array[Int],
    values: IndexedSeq[A],
):
  import RangeDatabase.*

  /** The number of ranges, both families. */
  def ranges: Int = starts4.length + starts6Hi.length

  /** The value of an address literal (see [[IpLiteral]]); `None` for text that is not one, and for an address no range holds. */
  def lookup(text: String): Option[A] =
    IpLiteral.parse(text).flatMap((v6, key) => lookup(v6, key))

  def lookup(address: InetAddress): Option[A] =
    address match
      case v4: Inet4Address => lookup(false, IpKey(hi = ipv4(v4.getAddress) << 32, lo = 0L))
      case v6: Inet6Address =>
        val bytes = v6.getAddress
        lookup(true, IpKey(hi = word(bytes, 0), lo = word(bytes, 8)))
      case _ => None

  /** `v6` is the family, `key` the address as [[IpLiteral]] reads it. */
  def lookup(v6: Boolean, key: IpKey): Option[A] =
    if !v6 then
      val ip = key.hi >>> 32
      val i = floor4(ip)
      if i >= 0 && ip <= ends4(i) then Some(values(index4(i))) else None
    else
      val i = floor6(key.hi, key.lo)
      if i >= 0 && compare(key.hi, key.lo, ends6Hi(i), ends6Lo(i)) <= 0 then Some(values(index6(i))) else None

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

object RangeDatabase:

  /** A database read from a source, and what was left out of it.
    *
    * @param malformed lines that are not a range (see [[RangeFormat.Row.Malformed]]), a line over [[MaxLine]] bytes, or a range
    *                  that overlaps an earlier one: left out and counted, never a failure
    * @param ignored   rows that are well formed and hold nothing (see [[RangeFormat.Row.Ignored]])
    */
  case class Loaded[A](database: RangeDatabase[A], malformed: Int, ignored: Int)

  def empty[A]: RangeDatabase[A] =
    RangeDatabase(
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyIntArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyLongArray,
      Array.emptyIntArray,
      IndexedSeq.empty,
    )

  /** The longest line read, in bytes: a real row is under 300. A file with a longer one is not this format, and a line
    * with no end must not be held in memory. */
  val MaxLine = 4096

  /** The most that is read of a file once it is unpacked, so that a small file that unpacks into gigabytes is not read to the end. */
  val MaxUnpackedBytes: Long = 1L << 30

  /** The most ranges a database holds, and so the memory a file can ask for: the real tables have a few hundred thousand, so
    * a file with many times as many is not one of them. Reading it fails rather than stops short, since half a table is a
    * wrong one. */
  val MaxRanges: Int = 4000000

  /** Reads a database from a file in `format`, gzipped or plain (told by the first two bytes, not by the name), the two
    * families together. The rows need not be sorted; a row that overlaps an earlier one is dropped. Blocking: it reads the
    * whole file. */
  def read[A](path: Path, format: RangeFormat[A]): Loaded[A] =
    val in = Files.newInputStream(path)
    try read(in, format)
    finally in.close()

  def read[A](source: InputStream, format: RangeFormat[A], maxRanges: Int = MaxRanges, maxUnpacked: Long = MaxUnpackedBytes): Loaded[A] =
    val buffered = BufferedInputStream(source, 1 << 16)
    buffered.mark(2)
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()
    val gzipped = first == 0x1f && second == 0x8b
    val unpacked: InputStream = if gzipped then GZIPInputStream(buffered, 1 << 16) else buffered
    val builder = Builder(format, maxRanges)
    forEachLine(Bounded(unpacked, maxUnpacked))(builder.add, builder.reject)
    builder.build()

  private class Builder[A](format: RangeFormat[A], maxRanges: Int):
    private val starts4 = ArrayBuilder.ofLong()
    private val ends4 = ArrayBuilder.ofLong()
    private val index4 = ArrayBuilder.ofInt()
    private val starts6Hi = ArrayBuilder.ofLong()
    private val starts6Lo = ArrayBuilder.ofLong()
    private val ends6Hi = ArrayBuilder.ofLong()
    private val ends6Lo = ArrayBuilder.ofLong()
    private val index6 = ArrayBuilder.ofInt()
    // The distinct values, each held once: a range has the index of its value, not the value.
    private val values = mutable.ArrayBuffer.empty[A]
    private val indexOf = mutable.HashMap.empty[A, Int]
    private var malformed = 0
    private var ignored = 0
    private var accepted = 0
    private var started = false

    def reject(): Unit = malformed += 1

    def add(line: String): Unit =
      // `first` is true for the first line the format does not skip anyway (a comment before the header is a Skip with or
      // without it, and must not use up the chance of the header to be taken for one): that line is read once without it, to
      // see whether it is one, and again with it.
      val row =
        if started || line.isBlank then format.parse(line, false)
        else if format.parse(line, false) == RangeFormat.Row.Skip then RangeFormat.Row.Skip
        else
          started = true
          format.parse(line, true)
      row match
        case RangeFormat.Row.Skip => ()
        case RangeFormat.Row.Malformed => malformed += 1
        case RangeFormat.Row.Ignored => ignored += 1
        case RangeFormat.Row.Entry(v6, from, to, value) =>
          accepted += 1
          if accepted > maxRanges then throw IOException(s"more than $maxRanges ranges")
          val index = indexOf.getOrElseUpdate(value, { values += value; values.length - 1 })
          if v6 then
            starts6Hi += from.hi
            starts6Lo += from.lo
            ends6Hi += to.hi
            ends6Lo += to.lo
            index6 += index
          else
            starts4 += from.hi >>> 32
            ends4 += to.hi >>> 32
            index4 += index

    def build(): Loaded[A] =
      val (os4, oe4, oi4, dropped4) = order4(starts4.result(), ends4.result(), index4.result())
      val (os6h, os6l, oe6h, oe6l, oi6, dropped6) =
        order6(starts6Hi.result(), starts6Lo.result(), ends6Hi.result(), ends6Lo.result(), index6.result())
      Loaded(
        database = RangeDatabase(os4, oe4, oi4, os6h, os6l, oe6h, oe6l, oi6, values.toIndexedSeq),
        malformed = malformed + dropped4 + dropped6,
        ignored = ignored,
      )

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
