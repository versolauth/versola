package versola.util.net

import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object DbIpCountryFormatSpec extends ZIOSpecDefault:

  private def read(lines: String*): RangeDatabase.Loaded[String] =
    RangeDatabase.read(ByteArrayInputStream(lines.mkString("\n").getBytes(StandardCharsets.UTF_8)), DbIpCountryFormat)

  private def country(loaded: RangeDatabase.Loaded[String], ip: String): Option[String] = loaded.database.lookup(ip)

  def spec = suite("DbIpCountryFormat")(
    test("reads the three columns of the file DB-IP publishes, IPv4 and IPv6 mixed") {
      val loaded = read(
        "1.0.0.0,1.0.0.255,AU",
        "8.8.8.0,8.8.8.255,US",
        "203.0.113.0,203.0.113.255,KZ",
        "2001:db8::,2001:db8:ffff:ffff:ffff:ffff:ffff:ffff,DE",
        "2a00:1450::,2a00:1450:ffff:ffff:ffff:ffff:ffff:ffff,IE",
      )
      assertTrue(
        loaded.database.ranges == 5,
        loaded.malformed == 0,
        country(loaded, "8.8.8.8").contains("US"),
        country(loaded, "203.0.113.7").contains("KZ"),
        country(loaded, "2001:db8::1").contains("DE"),
        country(loaded, "2a00:1450:4001:81b::200e").contains("IE"),
        country(loaded, "8.8.9.1").isEmpty,
      )
    },
    test("the code is upper-cased, so kz and KZ are one country, held once") {
      val loaded = read("10.0.0.0,10.0.0.255,kz", "10.0.1.0,10.0.1.255,KZ")
      assertTrue(country(loaded, "10.0.0.1").contains("KZ"), country(loaded, "10.0.1.1").contains("KZ"))
    },
    test("a header line is skipped, whatever its names are, and is not counted as malformed") {
      val loaded = read("ip_start,ip_end,country", "8.8.8.0,8.8.8.255,US")
      val other = read("\"first\",\"last\",\"code\"", "8.8.8.0,8.8.8.255,US")
      assertTrue(loaded.database.ranges == 1, loaded.malformed == 0, other.database.ranges == 1, other.malformed == 0)
    },
    test("columns after the country are tolerated, and a quoted field may hold a comma") {
      val loaded = read(
        "8.8.8.0,8.8.8.255,US,NA,North America,0,United States",
        "\"5.45.64.0\",\"5.45.79.255\",\"DE\",EU,Europe,1,\"Germany, Federal Republic of\"",
        "9.9.9.0,9.9.9.255,CH,EU,Europe,0,\"The \"\"Swiss\"\" Confederation, the\"",
      )
      assertTrue(
        loaded.database.ranges == 3,
        loaded.malformed == 0,
        country(loaded, "8.8.8.8").contains("US"),
        country(loaded, "5.45.64.9").contains("DE"),
        country(loaded, "9.9.9.9").contains("CH"),
      )
    },
    test("Windows line ends, blank lines and comments are fine") {
      val loaded = read("# DB-IP IP to Country Lite", "", "8.8.8.0,8.8.8.255,US\r", "  ", "5.45.64.0,5.45.79.255,DE\r")
      assertTrue(loaded.database.ranges == 2, loaded.malformed == 0)
    },
    test("ZZ and a country that is not two letters are ranges with no country: left out, not counted as malformed") {
      val loaded =
        read("1.0.0.0,1.0.0.255,US", "10.0.0.0,10.255.255.255,ZZ", "11.0.0.0,11.0.0.255,", "12.0.0.0,12.0.0.255,USA", "13.0.0.0,13.0.0.255,U1")
      assertTrue(loaded.database.ranges == 1, loaded.ignored == 4, loaded.malformed == 0, country(loaded, "10.0.0.1").isEmpty)
    },
    test("a line that is not a range is left out and counted, and the others are read") {
      val loaded = read(
        "1.0.0.0,1.0.0.255,US",
        "just text",
        "1.0.1.0,1.0.1.255",
        "300.0.0.0,300.0.0.255,US",
        "1.0.2.0,1.0.1.0,US",
        "1.0.3.0,2001:db8::1,US",
        "localhost,localhost,US",
        "1.0.4.0,1.0.4.255,ZZ,x",
        "5.45.64.0,5.45.79.255,DE",
      )
      assertTrue(
        loaded.database.ranges == 2,
        loaded.malformed == 6,
        loaded.ignored == 1,
        country(loaded, "1.0.0.1").contains("US"),
        country(loaded, "5.45.64.1").contains("DE"),
        country(loaded, "1.0.2.1").isEmpty,
      )
    },
    test("an unterminated quote runs to the end of its line and no further: the next line is read on its own") {
      val loaded = read("8.8.8.0,8.8.8.255,\"US", "5.45.64.0,\"5.45.79.255,DE", "9.9.9.0,9.9.9.255,CH")
      assertTrue(
        loaded.database.ranges == 2,
        loaded.malformed == 1,
        country(loaded, "8.8.8.8").contains("US"),
        country(loaded, "9.9.9.9").contains("CH"),
      )
    },
    test("a page of HTML is not read as a database") {
      val loaded = read("<html><body>", "<h1>404</h1>", "</body></html>")
      assertTrue(loaded.database.ranges == 0, loaded.malformed == 2)
    },
  )
