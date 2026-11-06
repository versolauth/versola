package versola.util.net

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object GeoResolverSpec extends ZIOSpecDefault:

  private val database = RangeDatabase
    .read(
      ByteArrayInputStream(
        List(
          "5.45.64.0,5.45.79.255,DE",
          "203.0.113.0,203.0.113.255,kz",
          "2001:db8::,2001:db8::ffff,DE",
        ).mkString("\n").getBytes(StandardCharsets.UTF_8),
      ),
      DbIpCountryFormat,
    )
    .database

  private val resolver = GeoResolver(RangeDatabaseLoader.fixed(database))

  private def count(result: String): UIO[Double] =
    Metric.counter("antifraud_geo_lookups_total").tagged(Set(MetricLabel("result", result))).value.map(_.count)

  /** The change of the three counters while `effect` runs, as (hit, miss, unavailable). */
  private def counted(effect: UIO[Any]): UIO[(Double, Double, Double)] =
    for
      before <- ZIO.foreach(List("hit", "miss", "unavailable"))(count)
      _ <- effect
      after <- ZIO.foreach(List("hit", "miss", "unavailable"))(count)
    yield (after(0) - before(0), after(1) - before(1), after(2) - before(2))

  private def asnCount(result: String): UIO[Double] =
    Metric.counter("antifraud_asn_lookups_total").tagged(Set(MetricLabel("result", result))).value.map(_.count)

  def spec = suite("GeoResolver")(
    test("gives the country, in upper case, for both families") {
      for
        de <- resolver.resolve("5.45.64.1")
        kz <- resolver.resolve("203.0.113.7")
        v6 <- resolver.resolve("2001:db8::1")
      yield assertTrue(de.contains("DE"), kz.contains("KZ"), v6.contains("DE"))
    },
    test("counts a hit, a miss (an address the database has no country for, or text that is no address) and, with no database, unavailable") {
      for
        hits <- counted(resolver.resolve("5.45.64.1") *> resolver.resolve("203.0.113.7"))
        misses <- counted(resolver.resolve("100.64.0.1") *> resolver.resolve("not an address"))
        unavailable <- counted(GeoResolver(RangeDatabaseLoader.off).resolve("5.45.64.1"))
        off <- counted(GeoResolver.off.resolve("5.45.64.1"))
      yield assertTrue(hits == (2.0, 0.0, 0.0), misses == (0.0, 2.0, 0.0), unavailable == (0.0, 0.0, 1.0), off == (0.0, 0.0, 1.0))
    },
    test("the series are its own: a lookup moves nothing of the ASN ones") {
      for
        before <- asnCount("hit")
        _ <- resolver.resolve("5.45.64.1")
        after <- asnCount("hit")
      yield assertTrue(after == before)
    },
    test("peek answers the same and counts nothing") {
      for
        peeked <- resolver.peek("5.45.64.1")
        counts <- counted(resolver.peek("5.45.64.1") *> resolver.peek("100.64.0.1"))
        nothing <- GeoResolver.off.peek("5.45.64.1")
      yield assertTrue(peeked.contains("DE"), counts == (0.0, 0.0, 0.0), nothing.isEmpty)
    },
    test("with no database the answer is nothing, never an error") {
      GeoResolver(RangeDatabaseLoader.off).resolve("5.45.64.1").map(found => assertTrue(found.isEmpty))
    },
  ) @@ TestAspect.sequential
