package versola.util.net

import java.io.InputStream
import java.nio.file.Path

/** The autonomous system that announces an address.
  *
  * @param asn     the AS number; the database holds the ones that fit an `Int` (every AS number in use does: the 32-bit
  *                numbers above 2^31 are private use and reserved)
  * @param country the ISO 3166 code of the country the AS is registered in, `ZZ` where the source gives none
  * @param name    the AS description of the source (the registry's name of the holder, with the AS handle)
  */
case class AsnInfo(asn: Int, country: String, name: String)

/** The table of the address ranges the autonomous systems announce: a [[RangeDatabase]] of the `ip2asn-combined.tsv` format of
  * iptoasn.com (see [[IptoasnFormat]]). The AS descriptions are shared between the ranges of one AS, so half a million
  * ranges take a few tens of megabytes. */
type AsnDatabase = RangeDatabase[AsnInfo]

object AsnDatabase:

  /** A database read from a file, and what was left out of it: `ignored` is the rows of AS 0, the space no AS announces. */
  type Loaded = RangeDatabase.Loaded[AsnInfo]

  val empty: AsnDatabase = RangeDatabase.empty

  val MaxLine: Int = RangeDatabase.MaxLine
  val MaxUnpackedBytes: Long = RangeDatabase.MaxUnpackedBytes
  val MaxRanges: Int = RangeDatabase.MaxRanges

  /** Reads a file of the `ip2asn-combined.tsv` format, gzipped or plain. Blocking: it reads the whole file. */
  def read(path: Path): Loaded = RangeDatabase.read(path, IptoasnFormat)

  def read(source: InputStream, maxRanges: Int = MaxRanges, maxUnpacked: Long = MaxUnpackedBytes): Loaded =
    RangeDatabase.read(source, IptoasnFormat, maxRanges, maxUnpacked)
