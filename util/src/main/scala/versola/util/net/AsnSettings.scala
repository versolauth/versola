package versola.util.net

import versola.util.Secret
import zio.*

/** The `antifraud.asn` block of the config of a service that looks addresses up in the ASN database.
  *
  * @param file            where the database is read from (a file of the iptoasn.com `ip2asn-combined.tsv` format, gzipped or
  *                        plain); empty switches the feature off, and nothing else changes
  * @param url             where a fresher one is downloaded from, to `file`; empty for none (the file is then read again when
  *                        it changes). A [[Secret]]: only its host is logged
  * @param refreshInterval how often `url` is fetched, or `file` looked at again
  * @param hostingKeywords the words of an AS name that make its network `hosting` (see [[AsnClassifier]])
  */
case class AsnSettings(
    file: String,
    url: Secret,
    refreshInterval: Duration,
    hostingKeywords: List[String],
):
  def enabled: Boolean = file.trim.nonEmpty

  def source: RangeSource = RangeSource(file = file, url = url, refreshInterval = refreshInterval)

object AsnSettings:

  /** What a config without the block means. */
  val off: AsnSettings =
    AsnSettings(file = "", url = Secret.fromString(""), refreshInterval = 24.hours, hostingKeywords = AsnClassifier.defaultKeywords)

  /** Every key has a default, and so has the block: a configuration written before it existed keeps loading, with the
    * feature off. Read with `Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config))`. */
  val config: Config[AsnSettings] =
    (RangeSource.config(off.refreshInterval) ++ Config.listOf("hostingKeywords", Config.string).optional).map: (source, keywords) =>
      AsnSettings(
        file = source.file,
        url = source.url,
        refreshInterval = source.refreshInterval,
        hostingKeywords = keywords.getOrElse(AsnClassifier.defaultKeywords),
      )
