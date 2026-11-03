package versola.util.net

/** How one line of a range database file is read: the format of a source (`ip2asn-combined.tsv` of iptoasn.com, the CSV of
  * DB-IP) as far as [[RangeDatabase]] is concerned. It holds no state, so a format is shared.
  *
  * @tparam A what an address range stands for (the AS, the country)
  */
trait RangeFormat[+A]:

  /** What `line` is. `first` is true for the first line that is not blank, which a format may take for a header. A line is
    * given without its end, and is at most [[RangeDatabase.MaxLine]] bytes long. */
  def parse(line: String, first: Boolean): RangeFormat.Row[A]

object RangeFormat:

  enum Row[+A]:
    /** A blank line, a comment or a header: nothing to hold and nothing to count. */
    case Skip

    /** Not a range: too few columns, an address that is not a literal, a range that ends before it starts or spans both
      * families, a value that is not one. Left out and counted, never a failure. */
    case Malformed

    /** A well-formed row that says nothing to hold (the source lists the space no AS announces, or a range with no known
      * country): counted apart from the malformed ones, which a file full of them would be. */
    case Ignored

    /** `v6` is the family of both addresses, which are the first and the last of the range, as [[IpLiteral]] reads them. */
    case Entry(v6: Boolean, from: IpKey, to: IpKey, value: A)

  /** The row of two address literals and a value, or `Malformed` when an address is not one, the two are of different
    * families, or the range ends before it starts. */
  def entry[A](first: String, last: String, value: A): Row[A] =
    (IpLiteral.parse(first), IpLiteral.parse(last)) match
      case (Some((v6, from)), Some((v6Last, to))) if v6 == v6Last && RangeDatabase.compare(from.hi, from.lo, to.hi, to.lo) <= 0 =>
        Row.Entry(v6 = v6, from = from, to = to, value = value)
      case _ => Row.Malformed
