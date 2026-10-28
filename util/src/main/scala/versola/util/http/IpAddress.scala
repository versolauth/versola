package versola.util.http

import java.net.{Inet4Address, Inet6Address, InetAddress}

/** An address as a rate-limit key. */
object IpAddress:
  private val FourParts = "\\d{1,3}(\\.\\d{1,3}){3}".r

  /** IPv4 as written, IPv6 by its /64 (one subscriber holds a whole /64, so a bucket per address is no bucket at all). An
    * IPv4-mapped IPv6 address is the IPv4 address. Text that is not an IP literal is `None`: it never reaches a resolver, and
    * the JDK's lenient forms (`1.2.3`, `16909060`) are not accepted, since they are not what a proxy writes.
    */
  def normalize(literal: String): Option[String] =
    if literal.isEmpty || literal.length > 45 || !literal.forall(c => c.isDigit || c == '.' || c == ':' || (c.toLower >= 'a' && c.toLower <= 'f')) then None
    else if literal.contains(':') || FourParts.matches(literal) then scala.util.Try(InetAddress.getByName(literal)).toOption.flatMap(normalize)
    else None

  def normalize(address: InetAddress): Option[String] =
    address match
      case v4: Inet4Address => Some(v4.getHostAddress)
      case v6: Inet6Address => Some("v6/" + v6.getAddress.take(8).map(b => f"${b & 0xff}%02x").mkString)
      case _ => None
