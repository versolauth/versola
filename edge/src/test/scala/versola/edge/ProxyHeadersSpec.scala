package versola.edge

import zio.http.{Header, Headers}
import zio.test.*

object ProxyHeadersSpec extends ZIOSpecDefault:

  private def headers(pairs: (String, String)*): Headers =
    Headers(pairs.map((name, value) => Header.Custom(name, value)))

  private def names(headers: Headers): Set[String] = headers.iterator.map(_.headerName.toLowerCase).toSet

  private def values(headers: Headers, name: String): List[String] =
    headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList

  private val connectionSpecific = List(
    "Connection" -> "keep-alive",
    "Proxy-Connection" -> "keep-alive",
    "Keep-Alive" -> "timeout=300",
    "TE" -> "trailers",
    "Transfer-Encoding" -> "chunked",
    "Upgrade" -> "websocket",
    "Proxy-Authorization" -> "Basic Zm9vOmJhcg==",
    "Proxy-Authenticate" -> "Basic",
  )

  private val forwarding = List(
    "Forwarded" -> "for=192.0.2.1;proto=https",
    "X-Forwarded-For" -> "192.0.2.1",
    "X-Forwarded-Host" -> "forged.example",
    "X-Forwarded-Proto" -> "https",
    "X-Forwarded-Port" -> "443",
    "X-Forwarded-Prefix" -> "/admin",
    "X-Real-IP" -> "192.0.2.1",
  )

  private val endToEnd = List(
    "Accept" -> "application/json",
    "Content-Type" -> "application/json",
    "Cache-Control" -> "no-cache",
    "X-Request-Trace" -> "trace-1",
    "X-Forward" -> "not a forwarding field",
  )

  private val toUpstreamTests = List(
    test("drops every connection-specific field, whatever the case of its name") {
      val result = ProxyHeaders.toUpstream(headers(connectionSpecific.map((n, v) => n.toLowerCase -> v)*), None)
      assertTrue(result.isEmpty)
    },
    test("drops every forwarding field the caller sent, whatever the case of its name") {
      val result = ProxyHeaders.toUpstream(headers((forwarding.map((n, v) => n.toUpperCase -> v) ++ forwarding)*), None)
      assertTrue(result.isEmpty)
    },
    test("drops the fields `Connection` names, and `Connection`, and keeps the others") {
      val result = ProxyHeaders.toUpstream(
        headers("Connection" -> "close, X-Secret", "connection" -> " X-Other ", "X-Secret" -> "1", "x-other" -> "2", "X-Kept" -> "3"),
        None,
      )
      assertTrue(names(result) == Set("x-kept"))
    },
    test("drops a field named by `Connection` that is not otherwise known, such as Authorization-like custom ones") {
      val result = ProxyHeaders.toUpstream(headers("Connection" -> "X-Trace-Hop", "X-Trace-Hop" -> "1", "X-Trace" -> "2"), None)
      assertTrue(names(result) == Set("x-trace"))
    },
    test("keeps the end-to-end fields as sent") {
      val result = ProxyHeaders.toUpstream(headers(endToEnd*), None)
      assertTrue(result.iterator.map(h => h.headerName.toLowerCase -> h.renderedValue).toList == endToEnd.map((n, v) => n.toLowerCase -> v))
    },
    test("sets X-Forwarded-For and X-Real-IP to edge's own address, replacing what the caller wrote") {
      val result = ProxyHeaders.toUpstream(headers((forwarding ++ connectionSpecific ++ endToEnd)*), Some("203.0.113.7"))
      assertTrue(
        values(result, "X-Forwarded-For") == List("203.0.113.7"),
        values(result, "X-Real-IP") == List("203.0.113.7"),
        !names(result).contains("forwarded"),
        names(result) == endToEnd.map(_._1.toLowerCase).toSet + "x-forwarded-for" + "x-real-ip",
      )
    },
    test("sets no address field when edge has no address to give") {
      val result = ProxyHeaders.toUpstream(headers((forwarding ++ endToEnd)*), None)
      assertTrue(names(result) == endToEnd.map(_._1.toLowerCase).toSet)
    },
  )

  private val toCallerTests = List(
    test("drops the connection-specific and forwarding fields of what the resource answered") {
      val result = ProxyHeaders.toCaller(headers((connectionSpecific ++ forwarding ++ endToEnd)*))
      assertTrue(names(result) == endToEnd.map(_._1.toLowerCase).toSet)
    },
    test("drops what the resource's `Connection` names") {
      val result = ProxyHeaders.toCaller(headers("Connection" -> "close, X-Hop", "X-Hop" -> "1", "X-Kept" -> "2"))
      assertTrue(names(result) == Set("x-kept"))
    },
  )

  def spec = suite("ProxyHeaders")(
    suite("toUpstream")(toUpstreamTests*),
    suite("toCaller")(toCallerTests*),
  )
