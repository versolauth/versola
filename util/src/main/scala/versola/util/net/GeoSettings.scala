package versola.util.net

import versola.util.Secret
import zio.*

/** The `antifraud.geo` block of the config of a service that looks the country of an address up in a DB-IP database.
  *
  * @param file            where the database is read from (the CSV of DB-IP's IP to Country Lite, see [[DbIpCountryFormat]], gzipped
  *                        or plain); empty switches the feature off, and nothing else changes
  * @param url             where a fresher one is downloaded from, to `file`; empty for none (the file is then read again when
  *                        it changes). A [[Secret]]: only its host is logged. It may hold `{yyyy}` and `{mm}`, the year and the
  *                        month (UTC) of the download, since the source publishes a file a month
  * @param refreshInterval how often `url` is fetched, or `file` looked at again
  */
case class GeoSettings(
    file: String,
    url: Secret,
    refreshInterval: Duration,
):
  def enabled: Boolean = file.trim.nonEmpty

  def source: RangeSource = RangeSource(file = file, url = url, refreshInterval = refreshInterval)

object GeoSettings:

  /** What a config without the block means. */
  val off: GeoSettings = GeoSettings(file = "", url = Secret.fromString(""), refreshInterval = 7.days)

  /** Every key has a default, and so has the block: a configuration written before it existed keeps loading, with the
    * feature off. Read with `Config.Nested("antifraud", Config.Nested("geo", GeoSettings.config))`. */
  val config: Config[GeoSettings] =
    RangeSource.config(off.refreshInterval).map: source =>
      GeoSettings(file = source.file, url = source.url, refreshInterval = source.refreshInterval)
