package versola.util.net

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object AsnResolverSpec extends ZIOSpecDefault:

  private val database = AsnDatabase
    .read(
      ByteArrayInputStream(
        List(
          "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS Hetzner Online GmbH",
          "203.0.113.0\t203.0.113.255\t64500\tKZ\tKAZTELECOM-AS JSC Kazakhtelecom",
          "2001:db8::\t2001:db8::ffff\t64502\tDE\tDOC-V6 Cloud Documentation",
        ).mkString("\n").getBytes(StandardCharsets.UTF_8),
      ),
    )
    .database

  private val resolver = AsnResolver(AsnDatabaseLoader.fixed(database), AsnClassifier(AsnClassifier.defaultKeywords))

  private def count(result: String): UIO[Double] =
    Metric.counter("antifraud_asn_lookups_total").tagged(Set(MetricLabel("result", result))).value.map(_.count)

  /** The change of the three counters while `effect` runs, as (hit, miss, unavailable). */
  private def counted(effect: UIO[Any]): UIO[(Double, Double, Double)] =
    for
      before <- ZIO.foreach(List("hit", "miss", "unavailable"))(count)
      _ <- effect
      after <- ZIO.foreach(List("hit", "miss", "unavailable"))(count)
    yield (after(0) - before(0), after(1) - before(1), after(2) - before(2))

  def spec = suite("AsnResolver")(
    test("gives the AS and its class, hosting for a provider and other for a carrier, for both families") {
      for
        hetzner <- resolver.resolve("5.45.64.1")
        carrier <- resolver.resolve("203.0.113.7")
        v6 <- resolver.resolve("2001:db8::1")
      yield assertTrue(
        hetzner.contains(AsnMatch(AsnInfo(24940, "DE", "HETZNER-AS Hetzner Online GmbH"), AsnClass.hosting)),
        carrier.map(_.asnClass).contains(AsnClass.other),
        carrier.map(_.info.asn).contains(64500),
        v6.map(_.asnClass).contains(AsnClass.hosting),
      )
    },
    test("counts a hit, a miss (an unannounced address, or text that is no address) and, with no database, unavailable") {
      for
        hits <- counted(resolver.resolve("5.45.64.1") *> resolver.resolve("203.0.113.7"))
        misses <- counted(resolver.resolve("100.64.0.1") *> resolver.resolve("not an address"))
        unavailable <- counted(AsnResolver(AsnDatabaseLoader.off, AsnClassifier(Nil)).resolve("5.45.64.1"))
        off <- counted(AsnResolver.off.resolve("5.45.64.1"))
      yield assertTrue(hits == (2.0, 0.0, 0.0), misses == (0.0, 2.0, 0.0), unavailable == (0.0, 0.0, 1.0), off == (0.0, 0.0, 1.0))
    },
    test("peek answers the same and counts nothing") {
      for
        peeked <- resolver.peek("5.45.64.1")
        counts <- counted(resolver.peek("5.45.64.1") *> resolver.peek("100.64.0.1"))
        nothing <- AsnResolver.off.peek("5.45.64.1")
      yield assertTrue(peeked.map(_.info.asn).contains(24940), counts == (0.0, 0.0, 0.0), nothing.isEmpty)
    },
    test("with no database the answer is nothing, never an error") {
      AsnResolver(AsnDatabaseLoader.off, AsnClassifier(AsnClassifier.defaultKeywords)).resolve("5.45.64.1").map(found => assertTrue(found.isEmpty))
    },
    test("the classifier is the operator's: another keyword list changes the class and nothing else") {
      val own = AsnResolver(AsnDatabaseLoader.fixed(database), AsnClassifier(List("kazakh")))
      for
        carrier <- own.resolve("203.0.113.7")
        hetzner <- own.resolve("5.45.64.1")
      yield assertTrue(
        carrier.map(_.asnClass).contains(AsnClass.hosting),
        hetzner.map(_.asnClass).contains(AsnClass.other),
        hetzner.map(_.info.asn).contains(24940),
      )
    },
  ) @@ TestAspect.sequential
