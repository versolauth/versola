package versola.antifraud

import scala.collection.mutable.ArrayBuffer

/** Reads an IP address literal, and nothing else: no host name, no zone, no port, so that nothing a client sends can
  * reach the resolver. `java.net.InetAddress.getByName` looks a name up whenever its argument is not a literal, and
  * `1.2.3.456` or `ab.cd.ef.ab` is not one, so it cannot be used on a request, nor on a value an operator types.
  *
  * The result is the address as [[IpKey]] (an IPv4 address in the high half), and whether it is IPv6. An IPv4-mapped
  * IPv6 address (`::ffff:203.0.113.7`, `::ffff:cb00:7107`) is the IPv4 address, as `InetAddress` has it.
  */
private[antifraud] object IpLiteral:

  /** Longest literal there is: eight groups of four hex digits with the colons, or six groups and a dotted quad. */
  private val MaxLength = 45

  def parse(text: String): Option[(Boolean, IpKey)] =
    val s = text.trim
    if s.isEmpty || s.length > MaxLength then None
    else if s.indexOf(':') >= 0 then parseV6(s)
    else parseV4(s).map(v4 => (false, IpKey(v4 << 32, 0L)))

  /** Four decimal groups of one to three digits, each at most 255, nothing else (no leading zeros: `010` would be
    * read as octal by some and as ten by others). */
  private def parseV4(s: String): Option[Long] =
    val parts = s.split('.')
    if parts.length != 4 || s.endsWith(".") then None
    else
      var value = 0L
      var i = 0
      var ok = true
      while ok && i < 4 do
        val part = parts(i)
        ok = part.nonEmpty && part.length <= 3 && part.forall(_.isDigit) && (part.length == 1 || part.head != '0')
        if ok then
          val n = part.toInt
          ok = n <= 255
          value = (value << 8) | n
        i += 1
      Option.when(ok)(value)

  /** Up to eight groups of one to four hex digits, one `::` at most standing for the missing groups, the last two
    * groups may be a dotted quad. */
  private def parseV6(s: String): Option[(Boolean, IpKey)] =
    if s.indexOf('%') >= 0 || s.indexOf("::") != s.lastIndexOf("::") then None
    else
      val halves = s.split("::", -1)
      if halves.length > 2 then None
      else
        val head = if halves(0).isEmpty then Array.empty[String] else halves(0).split(":", -1)
        val tail = if halves.length == 2 then (if halves(1).isEmpty then Array.empty[String] else halves(1).split(":", -1)) else Array.empty[String]
        val compressed = halves.length == 2
        val groups = ArrayBuffer.empty[Int]
        var ok = true

        def group(g: String): Unit =
          if !ok then ()
          else if g.isEmpty || g.length > 4 || !g.forall(c => c.isDigit || (c.toLower >= 'a' && c.toLower <= 'f')) then ok = false
          else groups += Integer.parseInt(g, 16)

        def groupsOf(parts: Array[String], last: Boolean): Unit =
          var i = 0
          while ok && i < parts.length do
            val p = parts(i)
            if last && i == parts.length - 1 && p.indexOf('.') >= 0 then
              parseV4(p) match
                case Some(v4) =>
                  groups += ((v4 >>> 16) & 0xffff).toInt
                  groups += (v4 & 0xffff).toInt
                case None => ok = false
            else group(p)
            i += 1

        groupsOf(head, last = !compressed)
        val fixed = groups.length
        groupsOf(tail, last = true)
        if !ok then None
        else if compressed && groups.length >= 8 then None
        else if !compressed && groups.length != 8 then None
        else
          val missing = 8 - groups.length
          val all = groups.take(fixed) ++ Seq.fill(missing)(0) ++ groups.drop(fixed)
          val hi = all.take(4).foldLeft(0L)((acc, g) => (acc << 16) | g)
          val lo = all.drop(4).foldLeft(0L)((acc, g) => (acc << 16) | g)
          // ::ffff:a.b.c.d is the IPv4 address a.b.c.d, in whichever spelling.
          if hi == 0L && (lo >>> 32) == 0xffffL then Some((false, IpKey((lo & 0xffffffffL) << 32, 0L)))
          else Some((true, IpKey(hi, lo)))
