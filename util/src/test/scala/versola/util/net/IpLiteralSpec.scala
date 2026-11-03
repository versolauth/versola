package versola.util.net

import zio.test.*

import java.net.InetAddress

object IpLiteralSpec extends ZIOSpecDefault:

  def spec = suite("IpLiteral")(
    test("reads an IPv4 address as an unsigned 32-bit value in the high half") {
      assertTrue(
        IpLiteral.parse("203.0.113.7") == Some((false, IpKey(0xcb007107L << 32, 0L))),
        IpLiteral.parse("255.255.255.255") == Some((false, IpKey(0xffffffffL << 32, 0L))),
        IpLiteral.parse("0.0.0.0") == Some((false, IpKey(0L, 0L))),
      )
    },
    test("reads IPv6 as InetAddress does, an IPv4-mapped address as the IPv4 one") {
      val bytes = InetAddress.getByName("2001:db8::1").getAddress
      def word(from: Int) = bytes.slice(from, from + 8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
      assertTrue(
        IpLiteral.parse("2001:db8::1") == Some((true, IpKey(word(0), word(8)))),
        IpLiteral.parse("::ffff:203.0.113.7") == IpLiteral.parse("203.0.113.7"),
      )
    },
    test("rejects what is not a literal, and an IPv4 address with a leading zero, a missing or an extra group") {
      val bad = List(
        "",
        "1.2.3",
        "1.2.3.4.5",
        "1.2.3.4.",
        ".1.2.3.4",
        "1..2.3",
        "01.2.3.4",
        "1.2.3.256",
        "1.2.3.-4",
        "a.b.c.d",
        "localhost",
        "1.2.3.4:80",
        "::g",
        "1:2:3:4:5:6:7:8:9",
      )
      assertTrue(bad.forall(IpLiteral.parse(_).isEmpty))
    },
    test("parseV4 reads a range of a string in place, and says -1 for anything else") {
      assertTrue(
        IpLiteral.parseV4("x1.2.3.4y", 1, 8) == 0x01020304L,
        IpLiteral.parseV4("1.2.3.4", 0, 7) == 0x01020304L,
        IpLiteral.parseV4("1.2.3.4", 0, 6) == -1L,
        IpLiteral.parseV4("1.2.3.04", 0, 8) == -1L,
      )
    },
  )
