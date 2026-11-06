package versola.util.net

import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/** What is specific to the iptoasn.com format: the rows of AS 0, the columns after the range, the bounds of an AS number. The
  * table and its search are the same for every format and are held to their cases in [[RangeDatabaseSpec]]. */
object AsnDatabaseSpec extends ZIOSpecDefault:

  private def read(lines: Iterable[String]): AsnDatabase.Loaded =
    AsnDatabase.read(ByteArrayInputStream((lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)))

  def spec = suite("AsnDatabase")(
    test("the rows of AS 0, the space nobody announces, hold nothing and are counted apart from the malformed ones") {
      val loaded = read(
        List(
          "0.0.0.0\t0.255.255.255\t0\tNone\tNot routed",
          "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.",
          "1.0.1.0\t1.0.3.255\t0\tNone\tNot routed",
        ),
      )
      assertTrue(
        loaded.database.ranges == 1,
        loaded.ignored == 2,
        loaded.malformed == 0,
        loaded.database.lookup("0.0.0.1").isEmpty,
        loaded.database.lookup("1.0.2.1").isEmpty,
        loaded.database.lookup("1.0.0.1").map(_.asn).contains(13335),
      )
    },
    test("a comment and a blank line are skipped, the description is optional and Windows line ends are fine") {
      val file = List("# a comment", "", "   ", "1.0.0.0\t1.0.0.255\t13335\tUS", "5.45.64.0\t5.45.79.255\t24940\tDE\tHetzner\r").mkString("\n")
      val result = AsnDatabase.read(ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)))
      assertTrue(
        result.malformed == 0,
        result.database.lookup("1.0.0.1") == Some(AsnInfo(asn = 13335, country = "US", name = "")),
        result.database.lookup("5.45.64.1") == Some(AsnInfo(asn = 24940, country = "DE", name = "Hetzner")),
      )
    },
    test("the country and the name are kept, and a country that is not two letters is ZZ") {
      val loaded = read(
        List(
          "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS Hetzner Online GmbH",
          "1.0.0.0\t1.0.0.255\t13335\tNone\tCloudflare, Inc. (ünïcode)",
          "2.0.0.0\t2.0.0.255\t64500\tkz\tLower case",
        ),
      )
      assertTrue(
        loaded.database.lookup("5.45.64.1") == Some(AsnInfo(asn = 24940, country = "DE", name = "HETZNER-AS Hetzner Online GmbH")),
        loaded.database.lookup("1.0.0.1") == Some(AsnInfo(asn = 13335, country = "ZZ", name = "Cloudflare, Inc. (ünïcode)")),
        loaded.database.lookup("2.0.0.1").map(_.country).contains("KZ"),
      )
    },
    test("an AS number that is not a number, is negative, empty, or does not fit an Int makes a malformed line, and the others are read") {
      val result = read(
        List(
          "1.0.0.0\t1.0.0.255\t13335\tUS\tGood one",
          "just text",
          "1.0.1.0\t1.0.1.255",
          "1.0.4.0\t1.0.4.255\tnot-a-number\tUS\tBad AS",
          "1.0.5.0\t1.0.5.255\t-5\tUS\tNegative AS",
          "1.0.6.0\t1.0.6.255\t4294967295\tUS\tOver an Int",
          "1.0.7.0\t1.0.7.255\t\tUS\tNo AS",
          "1.0.8.0\t1.0.8.255\t2147483647\tUS\tThe largest an Int holds",
          "5.45.64.0\t5.45.79.255\t24940\tDE\tAnother good one",
        ),
      )
      assertTrue(
        result.database.ranges == 3,
        result.malformed == 6,
        result.database.lookup("1.0.8.1").map(_.asn).contains(Int.MaxValue),
        result.database.lookup("1.0.4.1").isEmpty,
        result.database.lookup("1.0.6.1").isEmpty,
      )
    },
  )
