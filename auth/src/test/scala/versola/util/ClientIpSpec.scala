package versola.util

import zio.http.{Header, Headers}
import zio.test.*

object ClientIpSpec extends UnitSpecBase:

  private def headers(pairs: (String, String)*) = Headers(pairs.map((name, value) => Header.Custom(name, value)))

  private val XFF = "X-Forwarded-For"

  def spec = suite("ClientIp")(
    test("a single value of X-Real-IP is the address, as before") {
      assertTrue(ClientIp.from(headers("X-Real-IP" -> "203.0.113.7"), "X-Real-IP") == Some("203.0.113.7"))
    },
    test("the rightmost value of X-Forwarded-For is taken") {
      assertTrue(ClientIp.from(headers(XFF -> "198.51.100.99, 10.1.1.1, 203.0.113.7"), XFF) == Some("203.0.113.7"))
    },
    test("a spoofed leftmost value, with the proxy appending, never becomes the address") {
      val spoofed = headers(XFF -> "192.0.2.55, 203.0.113.7")
      assertTrue(ClientIp.from(spoofed, XFF) == Some("203.0.113.7"), !ClientIp.from(spoofed, XFF).contains("192.0.2.55"))
    },
    test("a header sent as several lines is read as one list, in order") {
      assertTrue(ClientIp.from(headers(XFF -> "198.51.100.99", XFF -> "10.0.0.3, 203.0.113.7"), XFF) == Some("203.0.113.7"))
    },
    test("IPv6 addresses are values like any other, and the header name is matched without case") {
      val h = headers(XFF -> "2001:db8::bad, 2001:db8:0:1::7")
      assertTrue(ClientIp.from(h, "x-forwarded-for") == Some("2001:db8:0:1::7"))
    },
    test("no header, an empty one, or empty list elements give no address") {
      assertTrue(
        ClientIp.from(Headers.empty, XFF).isEmpty,
        ClientIp.from(headers(XFF -> " , "), XFF).isEmpty,
        ClientIp.from(headers(XFF -> "203.0.113.7, "), XFF) == Some("203.0.113.7"),
      )
    },
  )
