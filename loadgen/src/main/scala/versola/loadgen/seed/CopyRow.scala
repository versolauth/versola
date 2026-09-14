package versola.loadgen.seed

import java.time.Instant

/** Encodes one `COPY ... WITH (FORMAT csv)` row (see [[SutSchema.copyStatement]] for why CSV and
  * not the default text format).
  *
  * The rule the whole encoding rests on: **every non-null value is quoted, and NULL is the only
  * unquoted field.** Postgres' CSV reader treats an unquoted empty field as NULL and a quoted
  * empty one (`""`) as the empty string, and that is the only distinction it draws -- so quoting
  * unconditionally makes "is this NULL" a property of this encoder rather than of the value. The
  * alternative, quoting only what needs it, means a user whose generated value happens to be
  * empty silently becomes NULL.
  *
  * CSV does no backslash processing, which is the second reason for it: `bytea` goes out as
  * `\x<hex>` and arrives at `byteain` unchanged, with no second layer of escaping to get wrong.
  */
final class CopyRow private (builder: StringBuilder):

  /** Counted rather than inferred from `builder.nonEmpty`. An earlier version separated on a
    * non-empty buffer, which silently dropped the separator when the *first* field was NULL --
    * NULL appends nothing -- shifting every remaining value one column to the left. No seeded
    * row starts with a NULL column today, so nothing failed; `SeedRowsSpec` pins it because the
    * next column added might.
    */
  private var written = 0

  private def separate(): CopyRow =
    if written > 0 then builder.append(',')
    written += 1
    this

  /** The one unquoted field. */
  def nullValue(): CopyRow =
    separate()

  def text(value: String): CopyRow =
    separate()
    builder.append('"')
    var index = 0
    while index < value.length do
      val char = value.charAt(index)
      // RFC 4180 / Postgres CSV: the quote character is escaped by doubling it. Nothing else is.
      if char == '"' then builder.append("\"\"") else builder.append(char)
      index += 1
    builder.append('"')
    this

  def optionalText(value: Option[String]): CopyRow =
    value.fold(nullValue())(text)

  def long(value: Long): CopyRow = text(value.toString)

  def short(value: Short): CopyRow = text(value.toString)

  def boolean(value: Boolean): CopyRow = text(if value then "t" else "f")

  def uuid(value: java.util.UUID): CopyRow = text(value.toString)

  def optionalUuid(value: Option[java.util.UUID]): CopyRow = value.fold(nullValue())(uuid)

  /** ISO-8601 with the `Z` offset, which is what `Instant.toString` produces and what Postgres'
    * `timestamptz` input accepts unambiguously -- unlike a local-looking timestamp, whose
    * interpretation would depend on the *server's* `TimeZone` rather than the seeder's.
    */
  def instant(value: Instant): CopyRow = text(value.toString)

  def optionalInstant(value: Option[Instant]): CopyRow = value.fold(nullValue())(instant)

  /** `bytea` in Postgres' hex input format. */
  def bytes(value: Array[Byte]): CopyRow = text(CopyRow.hex(value))

  def optionalBytes(value: Option[Array[Byte]]): CopyRow = value.fold(nullValue())(bytes)

  /** A `text[]` array literal. Elements are double-quoted inside the braces so an element
    * containing a comma or a brace cannot end the array early; the inner quotes are then escaped
    * for CSV by [[text]] doubling them, which is why this builds the literal as a string rather
    * than appending to the buffer directly.
    */
  def textArray(values: List[String]): CopyRow =
    text(values.map(element => "\"" + element.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").mkString("{", ",", "}"))

  def render: String = builder.toString

object CopyRow:
  def empty: CopyRow = CopyRow(StringBuilder())

  private val HexDigits = "0123456789abcdef".toCharArray

  private def hex(value: Array[Byte]): String =
    val out = StringBuilder(value.length * 2 + 2)
    out.append("\\x")
    var index = 0
    while index < value.length do
      val byte = value(index) & 0xff
      out.append(HexDigits(byte >>> 4)).append(HexDigits(byte & 0x0f))
      index += 1
    out.toString
