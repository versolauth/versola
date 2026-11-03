package versola.util.net

import scala.collection.mutable.ArrayBuffer

/** The CSV of DB-IP's IP to Country Lite database (`dbip-country-lite-YYYY-MM.csv`, https://db-ip.com/db/format/ip-to-country-lite/csv.html):
  * one range per line, the first address, the last address and the ISO 3166-1 alpha-2 code of the country, the two families
  * together, as RFC 4180 has it (a field may be quoted, and then may hold a comma). Only those three columns are read, so a
  * file with more after them (a continent, a name) is read the same. A header line is optional: the first line, if its first
  * field is not an address, is taken for one.
  *
  * The value of a range is the code in upper case, so `kz` and `KZ` are one value. `ZZ` and a code that is not two
  * letters are `Ignored`: the source gives no country for the range (reserved and unallocated space), so the address has
  * none, which is not the same as a country called `ZZ`.
  */
object DbIpCountryFormat extends RangeFormat[String]:

  override def parse(line: String, first: Boolean): RangeFormat.Row[String] =
    if line.isEmpty || line.charAt(0) == '#' || line.isBlank then RangeFormat.Row.Skip
    else
      val fields = split(line)
      if first && IpLiteral.parse(fields(0)).isEmpty then RangeFormat.Row.Skip
      else if fields.length < 3 then RangeFormat.Row.Malformed
      else
        val code = fields(2).trim.toUpperCase
        RangeFormat.entry(fields(0), fields(1), code) match
          // A range with no country is well formed, and holds nothing; one with a bad address is malformed.
          case RangeFormat.Row.Entry(_, _, _, _) if !isCode(code) || code == "ZZ" => RangeFormat.Row.Ignored
          case row => row

  private def isCode(code: String): Boolean =
    code.length == 2 && code.forall(c => (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))

  /** The fields of a CSV line, quotes removed (`""` inside a quoted field is one quote). A quote in the middle of an
    * unquoted field is kept as it is. */
  private[net] def split(line: String): Array[String] =
    val fields = ArrayBuffer.empty[String]
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while i < line.length do
      val c = line.charAt(i)
      if quoted then
        if c == '"' then
          if i + 1 < line.length && line.charAt(i + 1) == '"' then
            field.append('"')
            i += 1
          else quoted = false
        else field.append(c)
      else if c == '"' && field.isEmpty then quoted = true
      else if c == ',' then
        fields += field.toString
        field.clear()
      else field.append(c)
      i += 1
    fields += field.toString
    fields.toArray
