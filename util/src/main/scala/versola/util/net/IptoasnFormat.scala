package versola.util.net

/** The `ip2asn-combined.tsv` format of iptoasn.com: one range per line, `range_start`, `range_end`, `AS_number`,
  * `country_code` and `AS_description` separated by tabs, the two families together. Blank lines and lines that begin with `#`
  * are skipped. The rows of AS 0, which the source lists for the space no AS announces, hold nothing and are `Ignored`.
  */
object IptoasnFormat extends RangeFormat[AsnInfo]:

  override def parse(line: String, first: Boolean): RangeFormat.Row[AsnInfo] =
    if line.isEmpty || line.charAt(0) == '#' || line.isBlank then RangeFormat.Row.Skip
    else
      val t1 = line.indexOf('\t')
      val t2 = if t1 < 0 then -1 else line.indexOf('\t', t1 + 1)
      val t3 = if t2 < 0 then -1 else line.indexOf('\t', t2 + 1)
      if t3 < 0 then RangeFormat.Row.Malformed
      else
        val t4 = line.indexOf('\t', t3 + 1)
        val asn = parseAsn(line, t2 + 1, t3)
        if asn < 0 then RangeFormat.Row.Malformed
        else
          val country = normalizeCountry(line.substring(t3 + 1, if t4 < 0 then line.length else t4))
          val name = if t4 < 0 then "" else line.substring(t4 + 1).trim
          RangeFormat.entry(line.substring(0, t1), line.substring(t1 + 1, t2), AsnInfo(asn = asn.toInt, country = country, name = name)) match
            // Not announced: no information, so not held (and, being well formed, not malformed either).
            case RangeFormat.Row.Entry(_, _, _, _) if asn == 0 => RangeFormat.Row.Ignored
            case row => row

  /** An AS number from `line(from until to)` that fits an `Int`, or -1. */
  private def parseAsn(line: String, from: Int, until: Int): Long =
    if until - from < 1 || until - from > 10 then -1L
    else
      var value = 0L
      var i = from
      while i < until && value >= 0 do
        val c = line.charAt(i)
        value = if c >= '0' && c <= '9' then value * 10 + (c - '0') else -1L
        i += 1
      if value > Int.MaxValue then -1L else value

  private def normalizeCountry(raw: String): String =
    val code = raw.trim
    if code.length == 2 && code.forall(c => (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) then code.toUpperCase else "ZZ"
