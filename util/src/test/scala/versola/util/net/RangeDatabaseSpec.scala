package versola.util.net

import zio.test.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.zip.GZIPOutputStream
import scala.util.{Random, Try}

/** The table and its search are the same for every format, so each case runs once for each of the two: what a range stands
  * for is the only thing that differs (see [[AsnDatabaseSpec]] and [[DbIpCountryFormatSpec]] for what a format reads). */
object RangeDatabaseSpec extends ZIOSpecDefault:

  /** A format under test: how to write the row of a range with value number `tag`, and how to read the value back as text. */
  private case class Dialect[A](
      name: String,
      format: RangeFormat[A],
      row: (String, String, Int) => String,
      valueOf: A => String,
      expected: Int => String,
  )

  private def code(tag: Int): String =
    val i = tag % 675
    s"${('A' + i / 26).toChar}${('A' + i % 26).toChar}"

  private val iptoasn = Dialect[AsnInfo](
    name = "iptoasn.com TSV",
    format = IptoasnFormat,
    row = (from, to, tag) => s"$from\t$to\t${tag + 1}\tUS\tNetwork $tag",
    valueOf = _.asn.toString,
    expected = tag => (tag + 1).toString,
  )

  private val dbip = Dialect[String](
    name = "DB-IP country CSV",
    format = DbIpCountryFormat,
    row = (from, to, tag) => s"$from,$to,${code(tag)}",
    valueOf = identity,
    expected = code,
  )

  private def bytes(lines: Iterable[String]): Array[Byte] = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def suiteFor[A](d: Dialect[A]) =
    def read(lines: Iterable[String], maxRanges: Int = RangeDatabase.MaxRanges, maxUnpacked: Long = RangeDatabase.MaxUnpackedBytes) =
      RangeDatabase.read(ByteArrayInputStream(bytes(lines)), d.format, maxRanges, maxUnpacked)

    /** Tag 1..7, the two families together, in order, the last of each family at the very top of its space. */
    val sample = List(
      d.row("1.0.0.0", "1.0.0.255", 1),
      d.row("5.45.64.0", "5.45.79.255", 2),
      d.row("203.0.113.0", "203.0.113.255", 3),
      d.row("255.255.255.0", "255.255.255.255", 4),
      d.row("2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", 5),
      d.row("2a00:1450::", "2a00:1450:ffff:ffff:ffff:ffff:ffff:ffff", 6),
      d.row("ffff::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 7),
    )
    val loaded = read(sample)
    val db = loaded.database

    def at(ip: String): Option[String] = db.lookup(ip).map(d.valueOf)
    def is(ip: String, tag: Int): Boolean = at(ip).contains(d.expected(tag))

    suite(d.name)(
      suite("reading")(
        test("holds the ranges of both families, plain or gzipped (told by the first bytes), and an empty file is an empty database") {
          val packed = RangeDatabase.read(ByteArrayInputStream(gzip(bytes(sample))), d.format)
          val empty = RangeDatabase.read(ByteArrayInputStream(Array.emptyByteArray), d.format)
          assertTrue(
            db.ranges == 7,
            loaded.malformed == 0,
            packed.database.ranges == 7,
            packed.database.lookup("1.0.0.7").map(d.valueOf).contains(d.expected(1)),
            empty.database.ranges == 0,
            empty.database.lookup("1.0.0.7").isEmpty,
          )
        },
        test("a value is held once however many ranges have it") {
          val twice =
            read(List(d.row("10.0.0.0", "10.0.0.255", 3), d.row("10.9.0.0", "10.9.0.255", 3), d.row("2001:db8::", "2001:db8::ff", 3))).database
          assertTrue(twice.ranges == 3, twice.lookup("10.0.0.1").get.asInstanceOf[AnyRef].eq(twice.lookup("10.9.0.1").get.asInstanceOf[AnyRef]))
        },
      ),
      suite("lookup")(
        test("finds the value of an address inside a range, and of both ends of it") {
          assertTrue(
            is("1.0.0.0", 1),
            is("1.0.0.128", 1),
            is("1.0.0.255", 1),
            is("5.45.64.0", 2),
            is("5.45.79.255", 2),
            is("203.0.113.7", 3),
          )
        },
        test("finds nothing just outside a range, in a gap, below the first range and past the last one") {
          assertTrue(
            at("1.0.1.0").isEmpty,
            at("0.255.255.255").isEmpty,
            at("5.45.63.255").isEmpty,
            at("5.45.80.0").isEmpty,
            at("203.0.112.255").isEmpty,
            at("203.0.114.0").isEmpty,
            at("100.64.0.1").isEmpty,
          )
        },
        test("the last address of the space, an IPv4 address with the top bit set included") {
          assertTrue(is("255.255.255.255", 4), is("255.255.255.0", 4), at("255.255.254.255").isEmpty, at("128.0.0.1").isEmpty)
        },
        test("finds an IPv6 address, the ends of its range, and nothing outside it") {
          assertTrue(
            is("2001:db8::", 5),
            is("2001:db8::1", 5),
            is("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", 5),
            at("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff").isEmpty,
            at("2001:db9::").isEmpty,
            is("2a00:1450:4001:81b::200e", 6),
            at("::1").isEmpty,
          )
        },
        test("an address in the top half of the IPv6 space is compared as unsigned") {
          assertTrue(is("ffff::1", 7), is("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 7), at("fffe::").isEmpty, at("8000::").isEmpty)
        },
        test("an IPv4-mapped IPv6 address is looked up as the IPv4 address, in either spelling") {
          assertTrue(is("::ffff:5.45.64.1", 2), is("::ffff:52d:4001", 2), at("::ffff:5.45.80.1").isEmpty)
        },
        test("an InetAddress is looked up like its literal") {
          assertTrue(
            db.lookup(InetAddress.getByName("5.45.64.1")).map(d.valueOf).contains(d.expected(2)),
            db.lookup(InetAddress.getByName("2a00:1450:4001:81b::200e")).map(d.valueOf).contains(d.expected(6)),
            db.lookup(InetAddress.getByName("100.64.0.1")).isEmpty,
          )
        },
        test("text that is not an address literal finds nothing, and is never sent to a resolver") {
          assertTrue(
            db.lookup("localhost").isEmpty,
            db.lookup("1.2.3.456").isEmpty,
            db.lookup("").isEmpty,
            db.lookup("fe80::1%eth0").isEmpty,
            db.lookup("5.45.64.1:443").isEmpty,
          )
        },
        test("every range of a table with gaps is found at both ends and not beside them (binary search edges)") {
          // 1000 ranges of 100 addresses, 100 apart.
          def ip(i: Int, last: Int) = s"10.${i / 256}.${i % 256}.$last"
          val table = read((0 until 1000).map(i => d.row(ip(i, 0), ip(i, 99), i))).database
          def valueAt(text: String) = table.lookup(text).map(d.valueOf)
          assertTrue(
            table.ranges == 1000,
            (0 until 1000).forall(i => valueAt(ip(i, 0)).contains(d.expected(i)) && valueAt(ip(i, 99)).contains(d.expected(i))),
            (0 until 1000).forall(i => valueAt(ip(i, 100)).isEmpty && valueAt(ip(i, 255)).isEmpty),
            valueAt("9.255.255.255").isEmpty,
            valueAt("10.4.0.0").isEmpty,
          )
        },
        test("a single range, found at its one address and at neither side") {
          val one = read(List(d.row("7.7.7.7", "7.7.7.7", 8))).database
          assertTrue(one.lookup("7.7.7.7").map(d.valueOf).contains(d.expected(8)), one.lookup("7.7.7.6").isEmpty, one.lookup("7.7.7.8").isEmpty)
        },
      ),
      suite("bad input")(
        test("a comment, a blank line and then rows are read the same whether or not the format has a header, with nothing malformed") {
          val result = read(List("# a comment", "", sample(0), "# another", sample(1)))
          assertTrue(result.database.ranges == 2, result.malformed == 0)
        },
        test("a line longer than the format allows is left out and counted, wherever it is") {
          val long = d.row("1.0.0.0", "1.0.0.255", 9) + ("x" * 10000)
          val result = read(List(sample(1), long, sample(2)))
          assertTrue(result.database.ranges == 2, result.malformed == 1)
        },
        test("a file of no lines at all is not held in memory: it is one line too long") {
          val noLines = new Array[Byte](100000)
          Arrays.fill(noLines, 'a'.toByte)
          val result = RangeDatabase.read(ByteArrayInputStream(noLines), d.format)
          assertTrue(result.database.ranges == 0, result.malformed == 1)
        },
        test("rows out of order are read in order") {
          val result = read(Random(7).shuffle(sample))
          def valueAt(text: String) = result.database.lookup(text).map(d.valueOf)
          assertTrue(
            result.database.ranges == 7,
            result.malformed == 0,
            valueAt("5.45.64.1").contains(d.expected(2)),
            valueAt("1.0.0.1").contains(d.expected(1)),
            valueAt("203.0.113.9").contains(d.expected(3)),
            valueAt("2001:db8::7").contains(d.expected(5)),
            valueAt("2a00:1450::1").contains(d.expected(6)),
            valueAt("ffff::1").contains(d.expected(7)),
          )
        },
        test("a range that begins inside an earlier one is dropped and counted, whatever the order, and the earlier one stays") {
          val file = List(
            d.row("10.0.0.0", "10.0.0.255", 1),
            d.row("10.0.0.128", "10.0.1.255", 2),
            d.row("10.0.2.0", "10.0.2.255", 3),
            d.row("2001:db8::", "2001:db8::ffff", 4),
            d.row("2001:db8::8000", "2001:db8::1:0", 5),
          )
          val inOrder = read(file)
          val reversed = read(file.reverse)
          def values(r: RangeDatabase.Loaded[A]) =
            List("10.0.0.5", "10.0.0.200", "10.0.1.5", "10.0.2.5", "2001:db8::1", "2001:db8::9000").map(r.database.lookup(_).map(d.valueOf))
          val expected = List(Some(d.expected(1)), Some(d.expected(1)), None, Some(d.expected(3)), Some(d.expected(4)), Some(d.expected(4)))
          assertTrue(
            inOrder.database.ranges == 3,
            inOrder.malformed == 2,
            reversed.malformed == 2,
            values(inOrder) == expected,
            values(reversed) == expected,
          )
        },
        test("a file with more ranges than a database may hold fails to read, rather than asking for the memory") {
          val result = Try(read(sample, maxRanges = 3))
          val within = Try(read(sample, maxRanges = 7))
          assertTrue(result.isFailure, within.map(_.database.ranges).toOption.contains(7), RangeDatabase.MaxRanges >= 1000000)
        },
        test("a small gzip that unpacks past the bound fails to read, rather than being read to its end") {
          // 2 MB of one valid line repeated packs into a few kilobytes; read under a bound of 1 MB it must not get through.
          val line = (d.row("1.0.0.0", "1.0.0.255", 1) + "\n").getBytes(StandardCharsets.UTF_8)
          val bomb = gzip(Array.fill(2 * 1024 * 1024 / line.length)(line).flatten)
          val result = Try(RangeDatabase.read(ByteArrayInputStream(bomb), d.format, maxUnpacked = 1024 * 1024))
          val within = Try(RangeDatabase.read(ByteArrayInputStream(bomb), d.format))
          assertTrue(bomb.length < 64 * 1024, result.isFailure, within.isSuccess)
        },
        test("a gzip file that is cut short fails to read, rather than giving half a database") {
          val packed = gzip(bytes(sample))
          assertTrue(Try(RangeDatabase.read(ByteArrayInputStream(packed.take(packed.length - 12)), d.format)).isFailure)
        },
      ),
      suite("size and speed")(
        test("a database of 120 thousand ranges loads in a time that is linear, gzipped or plain, and finds every one of them") {
          def text(n: Long) = s"${(n >> 24) & 255}.${(n >> 16) & 255}.${(n >> 8) & 255}.${n & 255}"
          val v4 = (0 until 100000).map: i =>
            val start = 0x01000000L + i.toLong * 64
            d.row(text(start), text(start + 31), i)
          val v6 = (0 until 20000).map: i =>
            d.row(s"2001:db8:${i.toHexString}::", s"2001:db8:${i.toHexString}:ffff:ffff:ffff:ffff:ffff", i)
          val data = bytes(v4 ++ v6)
          val packed = gzip(data)
          val started = System.nanoTime()
          val fromPlain = RangeDatabase.read(ByteArrayInputStream(data), d.format)
          val plainMillis = (System.nanoTime() - started) / 1000000
          val startedPacked = System.nanoTime()
          val fromPacked = RangeDatabase.read(ByteArrayInputStream(packed), d.format)
          val packedMillis = (System.nanoTime() - startedPacked) / 1000000
          assertTrue(
            fromPlain.database.ranges == 120000,
            fromPacked.database.ranges == 120000,
            fromPlain.malformed == 0,
            // Generous, since util's specs run in parallel on whatever machine this is: a laptop does it in about half a second alone.
            // The bound is there to catch a load that is not linear (minutes at this size), not to time the machine.
            plainMillis < 15000,
            packedMillis < 15000,
            fromPlain.database.lookup("1.0.0.0").map(d.valueOf).contains(d.expected(0)),
            fromPlain.database.lookup("1.0.0.31").map(d.valueOf).contains(d.expected(0)),
            fromPlain.database.lookup("1.0.0.32").isEmpty,
            fromPlain.database.lookup("2001:db8:4e1f::1").map(d.valueOf).contains(d.expected(0x4e1f)),
          )
        },
      ),
    )

  def spec = suite("RangeDatabase")(suiteFor(iptoasn), suiteFor(dbip))
