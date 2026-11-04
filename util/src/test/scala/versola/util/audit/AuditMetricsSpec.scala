package versola.util.audit

import io.opentelemetry.api
import versola.util.SecureRandom
import versola.util.partitions.{PartitionReport, TableOwnership}
import zio.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.{PrometheusPublisher, prometheusLayer, publisherLayer}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.{Instant, LocalDate}

/** What a scrape of the metrics shows of a service that dropped or refused nothing. */
object AuditMetricsSpec extends ZIOSpecDefault:

  private val counterNames = List(
    "audit_events_dropped_total",
    "audit_events_blocked_total",
    "audit_events_unavailable_total",
    "audit_events_failed_total",
    "audit_events_rejected_total",
  )

  private val repository: AuditEventRepository = new AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] = ZIO.unit

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "svc", role = "svc", mayAlter = true))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** The scrape, once the publisher has taken the registry's contents of `source` (it samples on an interval of its own, and the
    * registry holds what other specs of the same process registered). */
  private def scrape(source: String): ZIO[PrometheusPublisher, Nothing, String] =
    ZIO
      .serviceWithZIO[PrometheusPublisher](_.get)
      .repeat(
        Schedule.spaced(100.millis) *> Schedule.identity[String].untilOutput: scraped =>
          counterNames.forall(name => scraped.linesIterator.exists(line => line.startsWith(name + "{") && line.contains(s"""source="$source""""))),
      )
      .timeout(30.seconds)
      .map(_.getOrElse(""))

  val spec = suite("AuditMetrics")(
    test("the counters of dropped, refused and failed events exist at 0 from the start, so that the first increment is a step a rate can see") {
      val source = AuditSource("svc-fresh")
      val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)
      ZIO.scoped:
        for
          _ <- AuditEvents
            .live(source, "node-1", AuditCatalog.of(Set.empty), table = "metrics_spec_audit_event")
            .build
            .provideSome[PrometheusPublisher & Scope](
              ZLayer.succeed(repository),
              ZLayer.succeed(settings),
              tracingLayer,
              SecureRandom.live,
            )
          scraped <- scrape("svc-fresh")
          lines = scraped.linesIterator.filter(_.contains("""source="svc-fresh"""")).toList
        yield assertTrue(
          counterNames.forall(name => lines.exists(line => line.startsWith(name + "{") && line.trim.split("\\s+").lift(1).contains("0.0"))),
        ).label(s"scraped audit lines: ${scraped.linesIterator.filter(_.contains("audit_")).take(30).mkString(" | ")}")
    },
  ).provideShared(
    ZLayer.succeed(MetricsConfig(100.millis)) >>> (publisherLayer >+> prometheusLayer),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
