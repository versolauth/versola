package versola.util.net

import zio.*
import zio.http.Client

/** The ASN database in use (see [[RangeDatabaseLoader]]). */
type AsnDatabaseLoader = RangeDatabaseLoader[AsnInfo]

object AsnDatabaseLoader:

  val off: AsnDatabaseLoader = RangeDatabaseLoader.off

  def fixed(database: AsnDatabase): AsnDatabaseLoader = RangeDatabaseLoader.fixed(database)

  val DownloadTimeout: Duration = RangeDatabaseLoader.DownloadTimeout
  val MaxDownloadBytes: Long = RangeDatabaseLoader.MaxDownloadBytes
  val RetryWhileEmpty: Duration = RangeDatabaseLoader.RetryWhileEmpty

  /** Reads the database the settings name and starts keeping it fresh; see [[RangeDatabaseLoader.start]]. */
  def start(settings: AsnSettings): ZIO[Client & Scope, Nothing, AsnDatabaseLoader] =
    RangeDatabaseLoader.start(settings.source, IptoasnFormat, "ASN database", AsnMetrics)
