package versola.antifraud

import zio.test.*

import java.net.InetAddress

object IpLiteralSpec extends ZIOSpecDefault:

  /** What `InetAddress` makes of a literal it accepts, as the key the parser is expected to make. */
  private def expected(literal: String): (Boolean, IpKey) =
    InetAddress.getByName(literal).getAddress match
      case Array(a, b, c, d) =>
        val v4 = ((a & 0xffL) << 24) | ((b & 0xffL) << 16) | ((c & 0xffL) << 8) | (d & 0xffL)
        (false, IpKey(v4 << 32, 0L))
      case bytes =>
        def word(from: Int) = bytes.slice(from, from + 8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
        (true, IpKey(word(0), word(8)))

  private val literals = List(
    "0.0.0.0",
    "203.0.113.7",
    "255.255.255.255",
    "10.0.0.1",
    "::",
    "::1",
    "0:0:0:0:0:0:0:1",
    "2001:db8::1",
    "2001:DB8:0:1::1",
    "2001:0db8:0000:0001:aaaa:bbbb:cccc:dddd",
    "fe80::",
    "::ffff:203.0.113.7",
    "::ffff:cb00:7107",
    "0:0:0:0:0:ffff:203.0.113.7",
    "64:ff9b::203.0.113.7",
    "1::",
    "1:2:3:4:5:6:7:8",
    "1:2:3:4:5:6:7::",
    "::2:3:4:5:6:7:8",
  )

  override def spec: Spec[TestEnvironment, Any] = suite("IpLiteral")(
    test("reads every spelling of an address as InetAddress does, without the resolver") {
      assertTrue(literals.forall(literal => IpLiteral.parse(literal).contains(expected(literal))))
    },
    test("an IPv4-mapped address is the IPv4 address, in either spelling") {
      assertTrue(
        IpLiteral.parse("::ffff:203.0.113.7") == IpLiteral.parse("203.0.113.7"),
        IpLiteral.parse("::ffff:cb00:7107") == IpLiteral.parse("203.0.113.7"),
        IpLiteral.parse("203.0.113.7").exists(!_._1),
      )
    },
    test("surrounding whitespace is ignored") {
      assertTrue(IpLiteral.parse("  203.0.113.7\n") == IpLiteral.parse("203.0.113.7"))
    },
    test("anything that is not an address literal is nothing: InetAddress would look it up") {
      val notLiterals = List(
        "",
        " ",
        "1.2.3.456",
        "ab.cd.ef.ab",
        "dead.beef.cafe.de",
        "1.2.3",
        "1.2.3.4.5",
        "1.2.3.",
        ".1.2.3",
        "01.2.3.4",
        "1.2.3.4:80",
        "1.2.3.4, 5.6.7.8",
        "www.example.com",
        "localhost",
        "fe80::1%eth0",
        "[::1]",
        ":::",
        "1::2::3",
        "1:2:3:4:5:6:7:8:9",
        "1:2:3:4:5:6:7",
        "12345::",
        "g::1",
        "::ffff:1.2.3.456",
        "1:2:3:4:5:6:7:1.2.3.4",
        "2001:db8:0:1::1/64",
        "203.0.113.7/32",
        "a" * 46,
      )
      assertTrue(notLiterals.forall(text => IpLiteral.parse(text).isEmpty))
    },
  )
