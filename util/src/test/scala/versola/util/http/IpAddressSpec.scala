package versola.util.http

import zio.test.*

object IpAddressSpec extends ZIOSpecDefault:

  def spec = suite("IpAddress.literal")(
    test("an IP literal is kept as written, an IPv6 address whole, not cut to the /64 the rate-limit key is") {
      assertTrue(
        IpAddress.literal("203.0.113.7").contains("203.0.113.7"),
        IpAddress.literal(" 203.0.113.7 ").contains("203.0.113.7"),
        IpAddress.literal("2001:db8:1:2::1").contains("2001:db8:1:2::1"),
        IpAddress.normalize("2001:db8:1:2::1") != IpAddress.literal("2001:db8:1:2::1"),
      )
    },
    test("anything that is not an IP literal is nothing: a name, a list, a partial address, a zone, an empty or long text") {
      assertTrue(
        IpAddress.literal("").isEmpty,
        IpAddress.literal("example.com").isEmpty,
        IpAddress.literal("unknown").isEmpty,
        IpAddress.literal("1.2.3").isEmpty,
        IpAddress.literal("16909060").isEmpty,
        IpAddress.literal("203.0.113.7, 198.51.100.1").isEmpty,
        IpAddress.literal("fe80::1%eth0").isEmpty,
        IpAddress.literal("a" * 60).isEmpty,
      )
    },
  )
