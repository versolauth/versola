package versola.util.net

import zio.*
import zio.http.Client

/** The country of an address, from the database in memory (see [[RangeDatabaseLoader]]): never a call to anything outside
  * the process, so it is as cheap as the request can afford and cannot fail or wait.
  */
trait GeoResolver:
  /** The ISO 3166-1 alpha-2 code (upper case) of the country of the address literal `ip`, counted in
    * `antifraud_geo_lookups_total` as a `hit`, a `miss` (the database has no country for it, or the text is no address) or
    * `unavailable` (no database is loaded: the feature is off, or the file is not there yet). */
  def resolve(ip: String): UIO[Option[String]]

  /** The same without counting it, for the second look at an address that was resolved already. */
  def peek(ip: String): UIO[Option[String]]

object GeoResolver:

  /** Resolves nothing. */
  val off: GeoResolver = new GeoResolver:
    override def resolve(ip: String): UIO[Option[String]] = GeoMetrics.lookup(GeoMetrics.Unavailable).as(None)

    override def peek(ip: String): UIO[Option[String]] = ZIO.none

  def apply(loader: RangeDatabaseLoader[String]): GeoResolver =
    new GeoResolver:
      private def find(ip: String): Option[Option[String]] = loader.current.map(_.lookup(ip))

      override def resolve(ip: String): UIO[Option[String]] =
        ZIO.suspendSucceed:
          val found = find(ip)
          GeoMetrics.lookup(found match
            case None => GeoMetrics.Unavailable
            case Some(Some(_)) => GeoMetrics.Hit
            case Some(None) => GeoMetrics.Miss).as(found.flatten)

      override def peek(ip: String): UIO[Option[String]] = ZIO.succeed(find(ip).flatten)

  /** Reads the database `settings` name (nothing when it names none), and keeps it fresh in the background until the scope ends. */
  val live: ZLayer[GeoSettings & Client & Scope, Nothing, GeoResolver] =
    ZLayer.fromZIO:
      for
        settings <- ZIO.service[GeoSettings]
        loader <- RangeDatabaseLoader.start(settings.source, DbIpCountryFormat, "country database", GeoMetrics)
      yield GeoResolver(loader)

/** The metrics of the country signal: `antifraud_geo_lookups_total{result}` and the `antifraud_geo_database_*` series. */
object GeoMetrics extends RangeMetrics("antifraud_geo")
