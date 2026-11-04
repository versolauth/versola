package versola.util.postgres

/** The name of a table that a statement names in its text (a table cannot be a bound parameter): a plain lower-case identifier, so
  * that it is never built from a request and needs no quoting. The longest is the one whose derived names still fit Postgres's 63
  * characters: the day partitions (`_YYYYMMDD`), their bounds check (`_YYYYMMDD_day`) and the default partition (`_default`). */
object TableName:
  private val Plain = "[a-z_][a-z0-9_]*".r

  val MaxLength = 50

  /** `name`, or an `IllegalArgumentException` that says what a table name may be. The refused name is not quoted back. */
  def validated(name: String): String =
    if name.length <= MaxLength && Plain.matches(name) then name
    else throw IllegalArgumentException(s"a table name is a plain identifier of lower-case letters, digits and underscores, at most $MaxLength characters")
