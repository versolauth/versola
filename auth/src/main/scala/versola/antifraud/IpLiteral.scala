package versola.antifraud

import versola.util.net.IpLiteral as NetIpLiteral

/** Reads an IP address literal, and nothing else (see [[NetIpLiteral]], the one parser the ASN database uses too), as
  * the lists and the velocity keys read addresses. */
private[antifraud] object IpLiteral:

  def parse(text: String): Option[(Boolean, IpKey)] =
    NetIpLiteral.parse(text).map((v6, key) => (v6, IpKey(hi = key.hi, lo = key.lo)))
