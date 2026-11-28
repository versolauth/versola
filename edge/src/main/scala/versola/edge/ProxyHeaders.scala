package versola.edge

import zio.http.{Header, Headers}

/** Which headers cross edge on a proxied call, in either direction.
  *
  * **Connection-specific fields** belong to one connection and are not edge's to pass on. RFC 9110 §7.6.1 has an intermediary
  * parse `Connection`, remove every field it names and then `Connection` itself, and "remove or replace" the fields known to need
  * removal whether or not they are named: `Proxy-Connection`, `Keep-Alive`, `TE`, `Transfer-Encoding` and `Upgrade`.
  * `Proxy-Authorization` and `Proxy-Authenticate` are consumed by the first proxy that is meant to read them (§11.7.1, §11.7.2) and are
  * dropped the same way.
  *
  * **Forwarding fields** say who the caller is and how the call arrived, and a caller can write any of them. Whatever came in is dropped
  * (RFC 7239 §8.1: the `Forwarded` field "cannot be relied upon to be correct", the client making the request included), and edge sets
  * `X-Forwarded-For` and `X-Real-IP` from the one address it has decided on: the connection's, or the header the operator named in
  * `client-ip` (see [[ClientAddress]]). `Forwarded` is not set at all: RFC 7239 §4 has it turned off by default, and §8.2 has it never
  * copied into a response. On the way back the same fields are dropped from what the resource answered.
  */
object ProxyHeaders:

  private val ConnectionSpecific = Set(
    "connection",
    "proxy-connection",
    "keep-alive",
    "te",
    "transfer-encoding",
    "upgrade",
    "proxy-authorization",
    "proxy-authenticate",
  )

  private val Forwarding = Set("forwarded", "x-real-ip")
  private val ForwardingPrefix = "x-forwarded-"

  /** What the resource is sent: the caller's fields without the connection-specific and forwarding ones, and edge's own address fields. */
  def toUpstream(headers: Headers, clientAddress: Option[String]): Headers =
    val kept = strip(headers)
    clientAddress.fold(kept): address =>
      kept.addHeader(Header.Custom("X-Forwarded-For", address)).addHeader(Header.Custom("X-Real-IP", address))

  /** What the caller is handed: the resource's answer without the connection-specific and forwarding fields. */
  def toCaller(headers: Headers): Headers = strip(headers)

  private def strip(headers: Headers): Headers =
    val named = headers.iterator
      .filter(_.headerName.equalsIgnoreCase("connection"))
      .flatMap(_.renderedValue.split(','))
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .toSet
    def dropped(header: Header): Boolean =
      val name = header.headerName.toLowerCase
      ConnectionSpecific.contains(name) || named.contains(name) || Forwarding.contains(name) || name.startsWith(ForwardingPrefix)
    Headers(headers.iterator.filterNot(dropped).toList)
