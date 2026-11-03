package versola.util.net

import zio.*
import zio.http.Client
import zio.metrics.{Metric, MetricLabel}

import java.time.Instant

/** An address looked up: its autonomous system, and what kind of network that is. */
case class AsnMatch(info: AsnInfo, asnClass: AsnClass)

/** The autonomous system of an address, from the database in memory (see [[AsnDatabaseLoader]]): never a call to anything
  * outside the process, so it is as cheap as the request can afford and cannot fail or wait.
  */
trait AsnResolver:
  /** The match for the address literal `ip`, counted in `antifraud_asn_lookups_total` as a `hit`, a `miss` (no AS announces
    * it, or the text is no address) or `unavailable` (no database is loaded: the feature is off, or the file is not there yet). */
  def resolve(ip: String): UIO[Option[AsnMatch]]

  /** The same without counting it, for the second look at an address that was resolved already. */
  def peek(ip: String): UIO[Option[AsnMatch]]

object AsnResolver:

  /** Resolves nothing. */
  val off: AsnResolver = new AsnResolver:
    override def resolve(ip: String): UIO[Option[AsnMatch]] = AsnMetrics.lookup(AsnMetrics.Unavailable).as(None)

    override def peek(ip: String): UIO[Option[AsnMatch]] = ZIO.none

  def apply(loader: AsnDatabaseLoader, classifier: AsnClassifier): AsnResolver =
    new AsnResolver:
      private def find(ip: String): Option[Option[AsnMatch]] =
        loader.current.map(_.lookup(ip).map(info => AsnMatch(info, classifier.classify(info))))

      override def resolve(ip: String): UIO[Option[AsnMatch]] =
        ZIO.suspendSucceed:
          val found = find(ip)
          AsnMetrics.lookup(found match
            case None => AsnMetrics.Unavailable
            case Some(Some(_)) => AsnMetrics.Hit
            case Some(None) => AsnMetrics.Miss).as(found.flatten)

      override def peek(ip: String): UIO[Option[AsnMatch]] = ZIO.succeed(find(ip).flatten)

  /** Reads the database `settings` name (nothing when it names none), and keeps it fresh in the background until the scope ends. */
  val live: ZLayer[AsnSettings & Client & Scope, Nothing, AsnResolver] =
    ZLayer.fromZIO:
      for
        settings <- ZIO.service[AsnSettings]
        loader <- AsnDatabaseLoader.start(settings)
      yield AsnResolver(loader, AsnClassifier(settings.hostingKeywords))

object AsnMetrics:
  val Hit = "hit"
  val Miss = "miss"
  val Unavailable = "unavailable"

  private val lookups = Metric.counter("antifraud_asn_lookups_total")
  private val loadedTimestamp = Metric.gauge("antifraud_asn_database_loaded_timestamp_seconds")
  private val ranges = Metric.gauge("antifraud_asn_database_ranges")
  private val refreshFailedTotal = Metric.counter("antifraud_asn_database_refresh_failed_total")

  private val tagged = Map(
    Hit -> lookups.tagged(Set(MetricLabel("result", Hit))),
    Miss -> lookups.tagged(Set(MetricLabel("result", Miss))),
    Unavailable -> lookups.tagged(Set(MetricLabel("result", Unavailable))),
  )

  /** `result` is `hit`, `miss` or `unavailable`: a closed set, since it is a label. */
  def lookup(result: String): UIO[Unit] = tagged(result).increment

  /** The database now in use: when its file was written (what a stale one is alerted on) and how many ranges it holds. */
  def loaded(writtenAt: Instant, rangeCount: Int): UIO[Unit] =
    loadedTimestamp.set(writtenAt.getEpochSecond.toDouble) *> ranges.set(rangeCount.toDouble)

  /** A download or a read that did not produce a database; the one in use stays. Alert on it increasing. */
  val refreshFailed: UIO[Unit] = refreshFailedTotal.increment
