package versola.util.net

import zio.*
import zio.http.URL

/** The `antifraud.asn` block of the config of a service that looks addresses up in the ASN database.
  *
  * @param file            where the database is read from (a file of the iptoasn.com `ip2asn-combined.tsv` format, gzipped or
  *                        plain); empty switches the feature off, and nothing else changes
  * @param url             where a fresher one is downloaded from, to `file`; empty for none (the file is then read again when
  *                        it changes)
  * @param refreshInterval how often `url` is fetched, or `file` looked at again
  * @param hostingKeywords the words of an AS name that make its network `hosting` (see [[AsnClassifier]])
  */
case class AsnSettings(
    file: String,
    url: String,
    refreshInterval: Duration,
    hostingKeywords: List[String],
):
  def enabled: Boolean = file.trim.nonEmpty

object AsnSettings:

  /** What a config without the block means. */
  val off: AsnSettings = AsnSettings(file = "", url = "", refreshInterval = 24.hours, hostingKeywords = AsnClassifier.defaultKeywords)

  /** Every key has a default, and so has the block: a configuration written before it existed keeps loading, with the
    * feature off. Read with `Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config))`. */
  val config: Config[AsnSettings] =
    (Config.string("file").withDefault(off.file) ++
      Config.string("url").withDefault(off.url).mapOrFail(validUrl) ++
      Config.duration("refreshInterval").withDefault(off.refreshInterval).mapOrFail(positive) ++
      Config.listOf("hostingKeywords", Config.string).optional).map: (file, url, interval, keywords) =>
      AsnSettings(file = file.trim, url = url, refreshInterval = interval, hostingKeywords = keywords.getOrElse(AsnClassifier.defaultKeywords))

  private def validUrl(raw: String): Either[Config.Error, String] =
    val url = raw.trim
    if url.isEmpty then Right(url)
    else
      URL.decode(url) match
        case Right(parsed) if parsed.host.exists(_.nonEmpty) && parsed.scheme.exists(s => s.encode == "https" || s.encode == "http") => Right(url)
        case _ => Left(Config.Error.InvalidData(message = "url must be an http or https URL, or empty"))

  private def positive(interval: Duration): Either[Config.Error, Duration] =
    if interval.isZero || interval.isNegative then Left(Config.Error.InvalidData(message = "refresh-interval must be longer than zero"))
    else Right(interval)
