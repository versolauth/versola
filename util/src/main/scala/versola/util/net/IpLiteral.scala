package versola.util.net

import scala.collection.mutable.ArrayBuffer

/** An address as two longs, the same for both families: an IPv4 address fills the high half, in its top 32 bits. */
case class IpKey(hi: Long, lo: Long)

object IpKey:

  /** Keeps the leading `bits` bits (of 128). */
  def mask(key: IpKey, bits: Int): IpKey =
    def top(x: Long, n: Int): Long = if n <= 0 then 0L else if n >= 64 then x else x & (-1L << (64 - n))
    IpKey(top(key.hi, bits), top(key.lo, bits - 64))

/** Reads an IP address literal, and nothing else: no host name, no zone, no port, so that nothing a client sends can
  * reach the resolver. `java.net.InetAddress.getByName` looks a name up whenever its argument is not a literal, and
  * `1.2.3.456` or `ab.cd.ef.ab` is not one, so it cannot be used on a request, nor on a value an operator types.
  *
  * The result is the address as [[IpKey]] (an IPv4 address in the high half), and whether it is IPv6. An IPv4-mapped
  * IPv6 address (`::ffff:203.0.113.7`, `::ffff:cb00:7107`) is the IPv4 address, as `InetAddress` has it.
  */
object IpLiteral:

  /** Longest literal there is: eight groups of four hex digits with the colons, or six groups and a dotted quad. */
  private val MaxLength = 45

  def parse(text: String): Option[(Boolean, IpKey)] =
    val s = text.trim
    if s.isEmpty || s.length > MaxLength then None
    else if s.indexOf(':') >= 0 then parseV6(s)
    else
      val v4 = parseV4(s, 0, s.length)
      if v4 < 0 then None else Some((false, IpKey(v4 << 32, 0L)))

  /** The IPv4 address `s` holds as an unsigned 32-bit value, or -1. Four decimal groups of one to three digits, each at
    * most 255, nothing else (no leading zeros: `010` would be read as octal by some and as ten by others). Reads the
    * text where it stands, without copying it, since a database of half a million ranges is read through it. */
  def parseV4(s: String, from: Int, until: Int): Long =
    var value = 0L
    var group = 0
    var digits = 0
    var groups = 0
    var i = from
    var ok = until - from >= 7 && until - from <= 15
    while ok && i < until do
      val c = s.charAt(i)
      if c >= '0' && c <= '9' then
        // A leading zero is a group of that one digit only.
        ok = !(digits == 1 && group == 0)
        group = group * 10 + (c - '0')
        digits += 1
        ok = ok && digits <= 3 && group <= 255
      else if c == '.' && digits > 0 then
        value = (value << 8) | group
        groups += 1
        group = 0
        digits = 0
        ok = groups < 4
      else ok = false
      i += 1
    if ok && digits > 0 && groups == 3 then (value << 8) | group else -1L

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
              val v4 = parseV4(p, 0, p.length)
              if v4 < 0 then ok = false
              else
                groups += ((v4 >>> 16) & 0xffff).toInt
                groups += (v4 & 0xffff).toInt
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
