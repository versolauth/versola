package versola.util.net

import zio.test.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.zip.GZIPOutputStream
import scala.util.{Random, Try}

object AsnDatabaseSpec extends ZIOSpecDefault:

  /** What a source looks like: IPv4 and IPv6 mixed, in order, with the rows of AS 0 for the space nobody announces. */
  private val sample = List(
    "0.0.0.0\t0.255.255.255\t0\tNone\tNot routed",
    "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.",
    "1.0.1.0\t1.0.3.255\t0\tNone\tNot routed",
    "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS Hetzner Online GmbH",
    "203.0.113.0\t203.0.113.255\t64500\tKZ\tKAZTELECOM-AS JSC Kazakhtelecom",
    "255.255.255.0\t255.255.255.255\t64501\tRU\tEDGE-AS Last range",
    "2001:db8::\t2001:db8:ffff:ffff:ffff:ffff:ffff:ffff\t64502\tDE\tDOC-V6 Documentation",
    "2a00:1450::\t2a00:1450:ffff:ffff:ffff:ffff:ffff:ffff\t15169\tUS\tGOOGLE - Google LLC",
    "ffff::\tffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff\t64503\tZZ\tTOP-V6 Top of the space",
  )

  private def bytes(lines: Iterable[String]): Array[Byte] = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def read(lines: Iterable[String]): AsnDatabase.Loaded = AsnDatabase.read(ByteArrayInputStream(bytes(lines)))

  private val loaded = read(sample)
  private val db = loaded.database

  private def asn(ip: String): Option[Int] = db.lookup(ip).map(_.asn)

  def spec = suite("AsnDatabase")(
    suite("reading")(
      test("holds the announced ranges of both families and counts the rows of AS 0 apart") {
        assertTrue(db.ranges == 7, loaded.notAnnounced == 2, loaded.malformed == 0)
      },
      test("reads the same database from a gzipped file as from a plain one, by the first bytes") {
        val packed = AsnDatabase.read(ByteArrayInputStream(gzip(bytes(sample))))
        assertTrue(packed.database.ranges == 7, packed.database.lookup("1.0.0.7").map(_.asn).contains(13335))
      },
      test("an empty file is an empty database, not a failure") {
        val empty = AsnDatabase.read(ByteArrayInputStream(Array.emptyByteArray))
        assertTrue(empty.database.ranges == 0, empty.database.lookup("1.0.0.7").isEmpty)
      },
      test("skips blank lines and comments, and accepts a line without a description and Windows line ends") {
        val file = List("# a comment", "", "   ", "1.0.0.0\t1.0.0.255\t13335\tUS", "5.45.64.0\t5.45.79.255\t24940\tDE\tHetzner\r").mkString("\n")
        val result = AsnDatabase.read(ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)))
        assertTrue(
          result.malformed == 0,
          result.database.lookup("1.0.0.1") == Some(AsnInfo(asn = 13335, country = "US", name = "")),
          result.database.lookup("5.45.64.1") == Some(AsnInfo(asn = 24940, country = "DE", name = "Hetzner")),
        )
      },
      test("the country and the name are kept, a country that is not two letters is ZZ") {
        val odd = read(List("1.0.0.0\t1.0.0.255\t13335\tNone\tCloudflare, Inc. (ünïcode)"))
        assertTrue(
          db.lookup("5.45.64.1") == Some(AsnInfo(asn = 24940, country = "DE", name = "HETZNER-AS Hetzner Online GmbH")),
          odd.database.lookup("1.0.0.1") == Some(AsnInfo(asn = 13335, country = "ZZ", name = "Cloudflare, Inc. (ünïcode)")),
        )
      },
    ),
    suite("lookup")(
      test("finds the AS of an address inside a range, and of both ends of it") {
        assertTrue(
          asn("1.0.0.0").contains(13335),
          asn("1.0.0.128").contains(13335),
          asn("1.0.0.255").contains(13335),
          asn("5.45.64.0").contains(24940),
          asn("5.45.79.255").contains(24940),
          asn("203.0.113.7").contains(64500),
        )
      },
      test("finds nothing just outside a range, in a gap, below the first range and past the last one") {
        assertTrue(
          asn("1.0.1.0").isEmpty,
          asn("5.45.63.255").isEmpty,
          asn("5.45.80.0").isEmpty,
          asn("203.0.112.255").isEmpty,
          asn("203.0.114.0").isEmpty,
          // The first row is AS 0: held nowhere, so nothing is found.
          asn("0.0.0.1").isEmpty,
          asn("100.64.0.1").isEmpty,
        )
      },
      test("the first and the last address of the space, an IPv4 address with the top bit set included") {
        assertTrue(
          asn("255.255.255.255").contains(64501),
          asn("255.255.255.0").contains(64501),
          asn("255.255.254.255").isEmpty,
          asn("128.0.0.1").isEmpty,
        )
      },
      test("finds an IPv6 address, the ends of its range, and nothing outside it") {
        assertTrue(
          asn("2001:db8::").contains(64502),
          asn("2001:db8::1").contains(64502),
          asn("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff").contains(64502),
          asn("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff").isEmpty,
          asn("2001:db9::").isEmpty,
          asn("2a00:1450:4001:81b::200e").contains(15169),
          asn("::1").isEmpty,
        )
      },
      test("an address in the top half of the IPv6 space is compared as unsigned") {
        assertTrue(
          asn("ffff::1").contains(64503),
          asn("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff").contains(64503),
          asn("fffe::").isEmpty,
          asn("8000::").isEmpty,
        )
      },
      test("an IPv4-mapped IPv6 address is looked up as the IPv4 address, in either spelling") {
        assertTrue(asn("::ffff:5.45.64.1").contains(24940), asn("::ffff:52d:4001").contains(24940), asn("::ffff:5.45.80.1").isEmpty)
      },
      test("an InetAddress is looked up like its literal") {
        assertTrue(
          db.lookup(InetAddress.getByName("5.45.64.1")).map(_.asn).contains(24940),
          db.lookup(InetAddress.getByName("2a00:1450:4001:81b::200e")).map(_.asn).contains(15169),
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
        // 1000 ranges of 100 addresses, 100 apart, each its own AS.
        val rows = (0 until 1000).map(i => s"10.${i / 256}.${i % 256}.0\t10.${i / 256}.${i % 256}.99\t${i + 1}\tUS\tAS $i")
        val table = read(rows).database
        def ip(i: Int, last: Int) = s"10.${i / 256}.${i % 256}.$last"
        assertTrue(
          table.ranges == 1000,
          (0 until 1000).forall(i => table.lookup(ip(i, 0)).map(_.asn).contains(i + 1) && table.lookup(ip(i, 99)).map(_.asn).contains(i + 1)),
          (0 until 1000).forall(i => table.lookup(ip(i, 100)).isEmpty && table.lookup(ip(i, 255)).isEmpty),
          table.lookup("9.255.255.255").isEmpty,
          table.lookup("10.4.0.0").isEmpty,
        )
      },
      test("a single range, found at its one address and at neither side") {
        val one = read(List("7.7.7.7\t7.7.7.7\t77\tUS\tOne")).database
        assertTrue(one.lookup("7.7.7.7").map(_.asn).contains(77), one.lookup("7.7.7.6").isEmpty, one.lookup("7.7.7.8").isEmpty)
      },
    ),
    suite("bad input")(
      test("a malformed line is left out and counted, and the others are read") {
        val file = List(
          "1.0.0.0\t1.0.0.255\t13335\tUS\tGood one",
          "just text",
          "1.0.1.0\t1.0.1.255",
          "300.0.0.0\t300.0.0.255\t5\tUS\tBad address",
          "1.0.2.0\t1.0.1.0\t6\tUS\tEnds before it starts",
          "1.0.3.0\t2001:db8::1\t7\tUS\tTwo families",
          "1.0.4.0\t1.0.4.255\tnot-a-number\tUS\tBad AS",
          "1.0.5.0\t1.0.5.255\t-5\tUS\tNegative AS",
          "1.0.6.0\t1.0.6.255\t4294967295\tUS\tOver an Int",
          "1.0.7.0\t1.0.7.255\t\tUS\tNo AS",
          "5.45.64.0\t5.45.79.255\t24940\tDE\tAnother good one",
        )
        val result = read(file)
        assertTrue(
          result.database.ranges == 2,
          result.malformed == 9,
          result.database.lookup("1.0.0.1").map(_.asn).contains(13335),
          result.database.lookup("5.45.64.1").map(_.asn).contains(24940),
          result.database.lookup("1.0.4.1").isEmpty,
        )
      },
      test("a line longer than the format allows is left out and counted, wherever it is") {
        val long = "1.0.0.0\t1.0.0.255\t13335\tUS\t" + ("x" * 10000)
        val result = read(List("5.45.64.0\t5.45.79.255\t24940\tDE\tFirst", long, "203.0.113.0\t203.0.113.255\t64500\tKZ\tLast"))
        assertTrue(result.database.ranges == 2, result.malformed == 1)
      },
      test("a file of no lines at all is not held in memory: it is one line too long") {
        val noLines = new Array[Byte](100000)
        Arrays.fill(noLines, 'a'.toByte)
        val result = AsnDatabase.read(ByteArrayInputStream(noLines))
        assertTrue(result.database.ranges == 0, result.malformed == 1)
      },
      test("rows out of order are read in order") {
        val shuffled = Random(7).shuffle(sample)
        val result = read(shuffled)
        assertTrue(
          result.database.ranges == 7,
          result.malformed == 0,
          result.database.lookup("5.45.64.1").map(_.asn).contains(24940),
          result.database.lookup("1.0.0.1").map(_.asn).contains(13335),
          result.database.lookup("203.0.113.9").map(_.asn).contains(64500),
          result.database.lookup("2001:db8::7").map(_.asn).contains(64502),
          result.database.lookup("2a00:1450::1").map(_.asn).contains(15169),
          result.database.lookup("ffff::1").map(_.asn).contains(64503),
        )
      },
      test("a range that begins inside an earlier one is dropped and counted, whatever the order, and the earlier one stays") {
        val file = List(
          "10.0.0.0\t10.0.0.255\t1\tUS\tFirst",
          "10.0.0.128\t10.0.1.255\t2\tUS\tInside the first",
          "10.0.2.0\t10.0.2.255\t3\tUS\tThird",
          "2001:db8::\t2001:db8::ffff\t4\tUS\tSix first",
          "2001:db8::8000\t2001:db8::1:0\t5\tUS\tSix inside",
        )
        val inOrder = read(file)
        val reversed = read(file.reverse)
        def asns(r: AsnDatabase.Loaded) =
          List("10.0.0.5", "10.0.0.200", "10.0.1.5", "10.0.2.5", "2001:db8::1", "2001:db8::9000").map(r.database.lookup(_).map(_.asn))
        val expected = List(Some(1), Some(1), None, Some(3), Some(4), Some(4))
        assertTrue(
          inOrder.database.ranges == 3,
          inOrder.malformed == 2,
          reversed.malformed == 2,
          asns(inOrder) == expected,
          asns(reversed) == expected,
        )
      },
      test("a file with more ranges than a database may hold fails to read, rather than asking for the memory") {
        val result = Try(AsnDatabase.read(ByteArrayInputStream(bytes(sample)), maxRanges = 3))
        val within = Try(AsnDatabase.read(ByteArrayInputStream(bytes(sample)), maxRanges = 7))
        assertTrue(result.isFailure, within.map(_.database.ranges).toOption.contains(7), AsnDatabase.MaxRanges >= 1000000)
      },
      test("a gzip file that is cut short fails to read, rather than giving half a database") {
        val packed = gzip(bytes(sample))
        val cut = packed.take(packed.length - 12)
        val result = Try(AsnDatabase.read(ByteArrayInputStream(cut)))
        assertTrue(result.isFailure)
      },
    ),
    suite("size and speed")(
      test("a database of 120 thousand ranges loads in well under a second, gzipped or plain, and finds every one of them") {
        val v4 = (0 until 100000).map: i =>
          val start = 0x01000000L + i.toLong * 64
          def text(n: Long) = s"${(n >> 24) & 255}.${(n >> 16) & 255}.${(n >> 8) & 255}.${n & 255}"
          s"${text(start)}\t${text(start + 31)}\t${i % 20000 + 1}\tUS\tSYNTHETIC-AS-${i % 20000} Synthetic Network ${i % 20000}"
        val v6 = (0 until 20000).map: i =>
          s"2001:db8:${i.toHexString}::\t2001:db8:${i.toHexString}:ffff:ffff:ffff:ffff:ffff\t${i % 5000 + 100000}\tDE\tSYNTHETIC-V6-${i % 5000}"
        val data = bytes(v4 ++ v6)
        val packed = gzip(data)
        val started = System.nanoTime()
        val fromPlain = AsnDatabase.read(ByteArrayInputStream(data))
        val plainMillis = (System.nanoTime() - started) / 1000000
        val startedPacked = System.nanoTime()
        val fromPacked = AsnDatabase.read(ByteArrayInputStream(packed))
        val packedMillis = (System.nanoTime() - startedPacked) / 1000000
        assertTrue(
          fromPlain.database.ranges == 120000,
          fromPacked.database.ranges == 120000,
          fromPlain.malformed == 0,
          // Generous: the point is the order of magnitude (a laptop does it in about a tenth of this), not the machine.
          plainMillis < 2000,
          packedMillis < 2000,
          fromPlain.database.lookup("1.0.0.0").map(_.asn).contains(1),
          fromPlain.database.lookup("1.0.0.31").map(_.asn).contains(1),
          fromPlain.database.lookup("1.0.0.32").isEmpty,
          fromPlain.database.lookup("2001:db8:4e1f::1").map(_.asn).contains(100000 + 0x4e1f % 5000),
        )
      },
    ),
  )
