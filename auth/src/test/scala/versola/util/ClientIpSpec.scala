package versola.util

import zio.http.{Header, Headers}
import zio.test.*

object ClientIpSpec extends UnitSpecBase:

  private def headers(pairs: (String, String)*) = Headers(pairs.map((name, value) => Header.Custom(name, value)))

  private val XFF = "X-Forwarded-For"

  def spec = suite("ClientIp")(
    test("a single value of X-Real-IP is the address, as before") {
      assertTrue(ClientIp.from(headers("X-Real-IP" -> "203.0.113.7"), "X-Real-IP", 1) == Some("203.0.113.7"))
    },
    test("with one hop the rightmost value of X-Forwarded-For is taken, so a value the client wrote on the left does not count") {
      val h = headers(XFF -> "198.51.100.99, 10.1.1.1, 203.0.113.7")
      assertTrue(ClientIp.from(h, XFF, 1) == Some("203.0.113.7"))
    },
    test("a spoofed leftmost value, with the proxy appending, never becomes the address") {
      val spoofed = headers(XFF -> "192.0.2.55, 203.0.113.7")
      assertTrue(ClientIp.from(spoofed, XFF, 1) == Some("203.0.113.7"), !ClientIp.from(spoofed, XFF, 1).contains("192.0.2.55"))
    },
    test("with two hops, a CDN in front of a load balancer, the second from the right is the client") {
      val h = headers(XFF -> "198.51.100.99, 203.0.113.7, 10.0.0.2")
      assertTrue(ClientIp.from(h, XFF, 2) == Some("203.0.113.7"), ClientIp.from(h, XFF, 1) == Some("10.0.0.2"))
    },
    test("fewer values than hops gives no address, and never the leftmost value") {
      assertTrue(
        ClientIp.from(headers(XFF -> "203.0.113.7"), XFF, 2).isEmpty,
        ClientIp.from(headers(XFF -> "203.0.113.7, 10.0.0.2"), XFF, 3).isEmpty,
      )
    },
    test("a header sent as several lines is read as one list, in order") {
      val h = headers(XFF -> "198.51.100.99", XFF -> "203.0.113.7, 10.0.0.2")
      assertTrue(ClientIp.from(h, XFF, 2) == Some("203.0.113.7"))
    },
    test("hops count applies to X-Forwarded-For only; another header is one address, read from the right if it is a list") {
      assertTrue(
        ClientIp.from(headers("X-Real-IP" -> "203.0.113.7"), "X-Real-IP", 2) == Some("203.0.113.7"),
        ClientIp.from(headers("X-Client-IP" -> "198.51.100.99, 203.0.113.7"), "X-Client-IP", 2) == Some("203.0.113.7"),
      )
    },
    test("IPv6 addresses are values like any other, and the header name is matched without case") {
      val h = headers(XFF -> "2001:db8::bad, 2001:db8:0:1::7")
      assertTrue(ClientIp.from(h, "x-forwarded-for", 1) == Some("2001:db8:0:1::7"), ClientIp.from(h, XFF, 2) == Some("2001:db8::bad"))
    },
    test("no header, an empty one, or empty list elements give no address; hops below 1 count as 1") {
      assertTrue(
        ClientIp.from(Headers.empty, XFF, 1).isEmpty,
        ClientIp.from(headers(XFF -> " , "), XFF, 1).isEmpty,
        ClientIp.from(headers(XFF -> "203.0.113.7, "), XFF, 1) == Some("203.0.113.7"),
        ClientIp.from(headers(XFF -> "198.51.100.99, 203.0.113.7"), XFF, 0) == Some("203.0.113.7"),
      )
    },
  )
