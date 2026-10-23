package versola.util

import zio.http.{Headers, Request}

/** The address of the client of a request, from the header the tenant's `ipHeader` names.
  *
  * A proxy that appends to `X-Forwarded-For` puts the address it saw at the right-hand end; whatever is to the left
  * was sent by the client or by an earlier hop, and the client can write anything there. So the value taken is the
  * `trustedProxyHops`-th from the right: 1 is the address the proxy next to this service saw, 2 is one hop further
  * out (a CDN in front of a load balancer), and so on. A header with fewer values than that gives no address (it
  * is never made up from the leftmost one), and the throttling that needs an address is then skipped, as it is
  * when no header is configured.
  *
  * Other headers (`X-Real-IP`, a custom one) are set by the proxy and hold one address, and the same rule with one hop
  * applies to them: a single value is what it is, and a list is read from the right.
  */
object ClientIp:
  private val ForwardedFor = "X-Forwarded-For"

  def fromRequest(request: Request, ipHeader: String, trustedProxyHops: Int): Option[String] =
    from(request.headers, ipHeader, trustedProxyHops)

  def from(headers: Headers, ipHeader: String, trustedProxyHops: Int): Option[String] =
    // A header may arrive as several lines, and each line may be a list: read them all, in order.
    val values = headers.toList.filter(_.headerName.equalsIgnoreCase(ipHeader)).flatMap(_.renderedValue.split(',')).map(_.trim).filter(_.nonEmpty)
    val hops = if ipHeader.equalsIgnoreCase(ForwardedFor) then math.max(1, trustedProxyHops) else 1
    Option.when(values.size >= hops)(values(values.size - hops))
