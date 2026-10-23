package versola.util

import zio.http.{Headers, Request}

/** The address of the client of a request, from the header the tenant's `ipHeader` names.
  *
  * A proxy that appends to `X-Forwarded-For` puts the address it saw at the right-hand end; whatever is to the left
  * was sent by the client or by an earlier hop, and the client can write anything there. So the rightmost value is
  * taken. A single value (`X-Real-IP`, set by the proxy) is what it is.
  */
object ClientIp:

  def fromRequest(request: Request, ipHeader: String): Option[String] =
    from(request.headers, ipHeader)

  def from(headers: Headers, ipHeader: String): Option[String] =
    // A header may arrive as several lines, and each line may be a list: read them all, in order.
    headers.toList.filter(_.headerName.equalsIgnoreCase(ipHeader)).flatMap(_.renderedValue.split(',')).map(_.trim).filter(_.nonEmpty).lastOption
